# State Machine Framework

Package: `net.runelite.client.plugins.microbot.statemachine`.

Opt-in base class `StateMachineScript<S extends Enum<S>>` for scripts modeled as explicit states + guarded transitions. Each tick: evaluate transitions top-to-bottom, fire first match, then invoke the current state's action. All transitions are logged and exposed via the agent server.

## Use it when
- Script has 3+ distinct phases (find → walk → interact → bank).
- Script is agent-generated — structured schema is easier for LLMs than ad-hoc loops.

Skip it for one-shot, event-driven, or single-linear-sequence scripts.

## API
- `initialState()` — required.
- `defineTransitions()` — returns `List<Transition<S>>`. Build with `Transition.<S>from(S).when(supplier, "expr").because("reason").goTo(S)`.
- `onState(S)` — per-state action; may block (runs off client thread).
- `StateSnapshot<S>` — thread-safe via `AtomicReference`; consumed by the debug endpoint.

## Rules
- Guards must be **pure** (no side effects, no blocking).
- Every state needs at least one exit transition; guard against null fields.
- Transition order = priority. Put high-priority exits (inventory full, death, errors) first.
- Always supply `because()` and a readable condition string — these surface in logs and the agent server.
- Do state-changing work in `onState()`, never in guards.
- Use the Queryable API (`Microbot.getRs2XxxCache()`) inside `onState()`.

## Known limitations (v1)
- No built-in blocking-state support (timeouts, pause states) — handle inline.
- No hierarchical states.

## Debug
`GET /statemachine/{scriptName}` on the agent server returns the live `StateSnapshot` (current state, last transition, reason, timestamp).

## See also
`docs/decisions/` for design rationale; non-negotiable rules in the repo-root `AGENTS.md`.
