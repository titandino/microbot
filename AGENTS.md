# Microbot Project Notes (for Codex)

## Microbot at a Glance
Microbot is a RuneLite-based Old School RuneScape client fork with an always-on hidden plugin that hosts automation scripts. It packages a shaded client for end users and keeps developer ergonomics via Queryable caches and script helpers under `microbot/util`. The build mirrors upstream RuneLite as a composite Gradle setup.

## Tech Stack
- Java target 11 (develop/run with JDK 17+), Gradle wrapper, composite builds (`cache`, `runelite-api`, `runelite-client`, `runelite-gradle-plugin`, `runelite-jshell`).
- RuneLite client + plugin APIs, LWJGL, Guice, Lombok, OkHttp, Gson, Logback/SLF4J.
- CI helper `ci/build.sh` bootstraps `glslang` then runs `./gradlew :buildAll`.

## Repo Map
- `runelite-client/` – Main client (`:client`) containing the Microbot plugin and shaded jar assembly.
- `runelite-api/` – RuneLite API included build consumed by the client.
- `runelite-gradle-plugin/` – Gradle plugins for assemble/index/jarsign tasks.
- `runelite-jshell/` – JShell support artifacts.
- `cache/` – Cache tools/build used by RuneLite.
- `docs/` – User/dev docs and static site assets.
- `config/` – Shared Checkstyle configuration.
- `ci/` – CI build helper script.

## How to Validate Changes
- Fast sanity: `./gradlew :client:compileJava`
- Full build (all included builds): `./gradlew buildAll`
- Assemble shaded client: `./gradlew :client:assemble`
- Test tasks are disabled by default. The allow-listed runners are:
  - `./gradlew :client:runUnitTests` — CI-safe unit tests (no client, no login). Includes the `ClientThreadGuardrailTest`.
  - `./gradlew :client:runTests` / `runDebugTests` — fuller test runs with timezone setup.
  - `./gradlew :client:runIntegrationTest` — live-client integration tests (requires running game).

## Non-Negotiable Rules
- Never instantiate caches or queryables directly; always use `Microbot.getRs2XxxCache().query()` or `.getStream()` (see `runelite-client/src/main/java/net/runelite/client/plugins/microbot/api/QUERYABLE_API.md`).
- Do not block or sleep on the RuneLite client thread; long work belongs on script/executor threads.
- Never use static sleeps like `sleep(12000)` to wait for game state. Always use conditional dynamic sleeps: `sleepUntil(BooleanSupplier awaitedCondition)` (optionally with a timeout as a safety net). Static delays are race-prone; conditional waits self-document the awaited state and are robust to latency/animation variation.
- Keep logging minimal; avoid PII/session identifiers and respect existing log levels/patterns.
- Preserve the hidden/always-on nature of `MicrobotPlugin` and its config panel wiring.
- Follow Checkstyle/Lombok patterns already in the codebase; do not downgrade security (e.g., telemetry token handling, HTTP clients) without discussion.

## Review Guidelines
- P0: Anything that can crash the client, block the client thread, break world hopping/login, corrupt cache/queryable invariants, or expose credentials/telemetry tokens.
- P1: Regressions to script loop timing, overlay correctness, plugin discovery/config panels, packaging (shaded jar/version props), or build reproducibility.
- Check threading (client vs script), cache access patterns, Gradle task wiring, and error handling around network calls.

## Agentic Testing Loop
- Architecture and protocol for autonomous script testing: `docs/AGENTIC_TESTING_LOOP.md`.
- Test mode: `-Dmicrobot.test.mode=true -Dmicrobot.test.script=<PluginName>`. Results land in `~/.runelite/test-results/`.

## Microbot CLI (Runtime Agent Control)
- `./microbot-cli` — bash wrapper at the repo root; all commands output JSON.
- Full CLI reference: `docs/MICROBOT_CLI.md`. HTTP API reference: `docs/AGENT_SERVER.md`.
- All tools for creating/debugging scripts (with debugging workflows): `docs/AGENT_SCRIPT_TOOLS.md`.
- The **Agent Server** plugin (port 8081) is enabled by default.
- Exposes: inventory, NPCs, objects, ground items, walking, banking, dialogues, widgets, skills, game state, login, profiles, keyboard, screenshots, settings, and script lifecycle.
- **Client-thread lookup** (offline, no game required): `./microbot-cli ct <method>` — checks if a RuneLite API method needs the client thread.

## In-Game Settings
- **Always use the settings search bar** — tab indices shift on game updates. See `docs/AGENT_SCRIPT_TOOLS.md` for the step-by-step workflow.
- Always verify changes via varbit: `./microbot-cli varbit <id>`.

## Entity Gotchas
- Per-entity footgun guides live in `docs/entity-guides/`. **Check `docs/entity-guides/README.md` first** before modifying any utility under `microbot/util/`.
- When you fix a bug caused by a hidden entity assumption, add a gotcha to the relevant guide.

## When Unsure
- **Creating/debugging scripts**: `docs/AGENT_SCRIPT_TOOLS.md` — every CLI command, HTTP endpoint, offline tool, and debugging workflow in one page.
- **Script authoring**: `runelite-client/.../microbot/CLAUDE.md` — threading, Rs2* utilities, cache, plugin-script lifecycle.
- **State machines**: `runelite-client/.../statemachine/CLAUDE.md` — prefer `StateMachineScript<S>` for scripts with 3+ states.
- **Architecture**: `docs/ARCHITECTURE.md` and `docs/decisions/`.
- **Development/setup**: `docs/development.md`; installation: `docs/installation.md`.
- Still unclear? Ask in Discord (link in `README.md`) or leave TODO with assumption noted.

