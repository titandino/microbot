# State Machine Framework — Developer Guide

> **Package:** `net.runelite.client.plugins.microbot.statemachine`
> **Since:** v1.0

## What This Is

An opt-in base class for Microbot scripts that models automation logic as an explicit state machine. Instead of ad-hoc `if/else` branches in a `run()` loop, you declare:

- **States** — an enum of what the script can be doing
- **Transitions** — guarded moves between states, with human-readable reasons
- **Actions** — what to do in each state

The framework evaluates transitions each tick, fires at most one, then invokes the action. Every transition is logged and queryable via the agent server.

## When to Use It

✅ **Use `StateMachineScript`** when your script has 3+ distinct phases (e.g. find target → walk → interact → bank).

✅ **Use it for agent-generated scripts** — the structured schema (states, transitions, guards) is easier for LLMs to generate correctly than ad-hoc loops.

❌ **Don't use it** for event-driven scripts, one-shot scripts, or scripts with a single linear sequence.

## Quick Start

### 1. Define your states

```java
public class MyScript extends StateMachineScript<MyScript.State> {

    enum State {
        FIND_TARGET,
        WALK_TO_TARGET,
        INTERACT,
        BANK_LOOT,
        ERROR
    }
```

### 2. Set the initial state

```java
    @Override
    protected State initialState() {
        return State.FIND_TARGET;
    }
```

### 3. Define transitions

Transitions are evaluated **top-to-bottom** each tick. First match wins.

```java
    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(
            Transition.<State>from(State.FIND_TARGET)
                .when(() -> targetNpc != null, "targetNpc != null")
                .because("NPC found within range")
                .goTo(State.WALK_TO_TARGET),

            Transition.<State>from(State.FIND_TARGET)
                .when(() -> Rs2Inventory.isFull(), "Rs2Inventory.isFull()")
                .because("Inventory full, banking instead")
                .goTo(State.BANK_LOOT),

            Transition.<State>from(State.WALK_TO_TARGET)
                .when(() -> targetNpc != null && targetNpc.getDistanceFromPlayer() < 2,
                      "distanceFromPlayer < 2")
                .because("Close enough to interact")
                .goTo(State.INTERACT),

            Transition.<State>from(State.INTERACT)
                .when(() -> Rs2Inventory.isFull() || targetNpc == null,
                      "inventory full or target gone")
                .because("Got loot or target gone")
                .goTo(State.BANK_LOOT),

            Transition.<State>from(State.BANK_LOOT)
                .when(() -> Rs2Inventory.isEmpty(), "Rs2Inventory.isEmpty()")
                .because("Banking complete")
                .goTo(State.FIND_TARGET)
        );
    }
```

### 4. Implement state actions

Use the **Queryable API** (see `api/QUERYABLE_API.md`) instead of legacy static `Rs2*` methods where possible.

```java
    @Override
    protected void onState(State state) {
        switch (state) {
            case FIND_TARGET:
                // ✅ Queryable API — preferred
                targetNpc = Microbot.getRs2NpcCache().query()
                    .withName("Guard")
                    .nearest();
                break;
            case WALK_TO_TARGET:
                if (targetNpc != null) {
                    Rs2Walker.walkTo(targetNpc.getWorldLocation());
                }
                break;
            case INTERACT:
                if (targetNpc != null) {
                    Microbot.getRs2NpcCache().query()
                        .withName("Guard")
                        .nearest()
                        .click("Attack");
                }
                break;
            case BANK_LOOT:
                Rs2Bank.openNearest();
                break;
            case ERROR:
                // Do nothing; wait for manual intervention
                break;
        }
    }
```

### 5. Wire it up in your `run()` method

Call `step()` inside the scheduled lambda — **not** the state machine logic directly.

```java
    public boolean run(MyConfig config) {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                step();  // ← the entire engine
            } catch (Exception ex) {
                log.error("Error", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }
}
```

## API Reference

### `StateMachineScript<S extends Enum<S>>`

| Method | Description |
|--------|-------------|
| `abstract S initialState()` | Return the starting state |
| `abstract List<Transition<S>> defineTransitions()` | Return all transitions (called once) |
| `abstract void onState(S state)` | Execute the action for the current state |
| `final boolean step()` | Run one tick: check guard → evaluate transitions → fire → execute action |
| `S getCurrentState()` | Current state (reliable only on script thread) |
| `StateSnapshot<S> getSnapshot()` | Thread-safe snapshot for debug (callable from any thread) |
| `void forceState(S state, String reason)` | Manually set state (for tests/resets) |
| `void onTransition(S from, S to, String reason)` | Hook: called when a transition fires (override for custom logging) |
| `S onError(S state, Exception e)` | Hook: called when `onState()` throws. Return a state to transition to, or null to stay |

### `Transition<S>` — Fluent Builder

```java
Transition.from(STATE_A)                     // start building
    .when(BooleanSupplier condition)          // guard (required)
    .when(condition, "expression string")     // guard + debug expression
    .because("human-readable reason")         // why this fires (optional but recommended)
    .goTo(STATE_B)                            // target state → returns Transition<S>
```

### `StateSnapshot<S>` — Debug DTO

Immutable, thread-safe snapshot published each tick via `AtomicReference`.

| Field | Type | Description |
|-------|------|-------------|
| `currentState` | `S` | Current state |
| `previousState` | `S` | State before last transition |
| `lastTransitionReason` | `String` | Why the last transition fired |
| `lastTransitionAt` | `Instant` | When the last transition fired |
| `stateEnteredAt` | `Instant` | When the current state was entered |
| `loopCount` | `long` | Total ticks executed |
| `transitionCount` | `long` | Total transitions fired |
| `pendingTransitions` | `List<PendingTransition<S>>` | Transitions available from current state + whether their guard is currently true |
| `recentTrace` | `List<TraceEntry<S>>` | Last 50 transitions (ring buffer) |

### Debug Endpoint

```
GET /debug/snapshot                    → list all registered state machines
GET /debug/snapshot?script=MyScript    → full snapshot for one script (partial name match)
```

Example response:
```json
{
  "script": "BoneCollectorScript",
  "currentState": "WALK_TO_TARGET",
  "previousState": "FIND_TARGET",
  "lastTransitionReason": "NPC found within range",
  "lastTransitionAt": "2024-01-15T14:23:01.442Z",
  "stateEnteredAt": "2024-01-15T14:23:01.442Z",
  "loopCount": 147,
  "transitionCount": 12,
  "msInCurrentState": 3841,
  "pendingTransitions": [
    {
      "to": "INTERACT",
      "condition": "distanceFromPlayer < 2",
      "currentlyTrue": false,
      "because": "Close enough to interact"
    }
  ],
  "recentTrace": [
    {"from": "FIND_TARGET", "to": "WALK_TO_TARGET", "reason": "NPC found within range", "at": "..."}
  ]
}
```

## Best Practices

### 1. Keep states small — one clear action per state

⚠️ If `onState(BANK_LOOT)` has 40 lines of banking logic, you've lost the debugging benefit. Break it into sub-states: `OPEN_BANK`, `DEPOSIT_ITEMS`, `CLOSE_BANK`.

### 2. Always provide `because()` and condition expressions

```java
// ❌ Bad — debug output shows "FIND_TARGET → WALK_TO_TARGET" with no context
Transition.from(FIND_TARGET).when(() -> targetNpc != null).goTo(WALK_TO_TARGET)

// ✅ Good — debug output explains WHY
Transition.from(FIND_TARGET)
    .when(() -> targetNpc != null, "targetNpc != null")
    .because("NPC found within range")
    .goTo(WALK_TO_TARGET)
```

### 3. Every state should have at least one exit transition

A state with no exit transition is a dead end. The agent can detect this statically. If a state intentionally has no exit (e.g., `ERROR`), document why.

### 4. Guard against null in transition conditions

```java
// ❌ Can throw NPE if targetNpc is null
.when(() -> targetNpc.getDistanceFromPlayer() < 2)

// ✅ Null-safe
.when(() -> targetNpc != null && targetNpc.getDistanceFromPlayer() < 2)
```

The framework catches guard exceptions and logs a warning, but the transition won't fire.

### 5. Use the Queryable API in `onState()`

Prefer `Microbot.getRs2NpcCache().query()...` over legacy `Rs2Npc.getNpc()`. See `api/QUERYABLE_API.md`.

### 6. Transition order matters

Transitions are evaluated top-to-bottom. Put more specific conditions first:

```java
// ✅ Specific condition checked first
Transition.from(FIND_TARGET)
    .when(() -> Rs2Inventory.isFull()).because("Inventory full").goTo(BANK_LOOT),
Transition.from(FIND_TARGET)
    .when(() -> targetNpc != null).because("NPC found").goTo(WALK_TO_TARGET)
```

### 7. Guards must be pure (no side effects)

Guard conditions may be evaluated more than once per tick (e.g. for debug snapshot generation after a transition fires). Never put logging, counter increments, or state mutations inside a guard:

```java
// ❌ Bad — side effect in guard
.when(() -> { attempts++; return attempts > 3; })

// ✅ Good — pure boolean check
.when(() -> attempts > 3, "attempts > 3")
```

## Blocking States (v1 Limitation)

`onState()` may call blocking methods like `sleepUntil()`, `Rs2Walker.walkTo()`, or `Rs2Bank.openNearest()`. This is tolerated in v1 for compatibility with existing Rs2* utilities.

**Consequence**: While a state is blocking, transitions cannot be evaluated, and the health endpoint may report the script as "stalled" even though it's working normally. Use `msInCurrentState` in the snapshot to distinguish real stalls from expected blocking.

## Error Handling

If `onState()` throws, the framework:
1. Calls `onError(state, exception)` — override to customize
2. If `onError` returns a non-null state different from current → transitions there
3. If `onError` returns null → stays in current state
4. The exception is **never** propagated to the scheduler (loop won't die)

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Script stuck in one state | No exit transition matches | Check guards; add debug logging in `onState()` |
| Rapid state oscillation | Two transitions keep firing alternately | Add a cooldown state between them or refine guards |
| "Script not found" in debug endpoint | Script not started or already shut down | `StateMachineScript` registers on first `step()` call |
| Transition guard NPE in logs | Null reference in condition lambda | Add null checks (see Best Practice #4) |
| State machine not initialized | `step()` never called | Ensure your `run(config)` method calls `step()` in the lambda |

## File Reference

| File | Purpose |
|------|---------|
| `StateMachineScript.java` | Base class with engine, registry, snapshot publishing |
| `Transition.java` | Immutable transition definition + fluent builder |
| `StateSnapshot.java` | Thread-safe debug snapshot DTO |
| `PendingTransition.java` | Per-transition debug info |
| `StateMachineDebugHandler.java` | Agent server endpoint at `/debug/snapshot` |
