# Detection Hardening Plan

Audit of Microbot detection surfaces and a phased plan to harden them. Scope: anything a server-side or client-side anticheat could sample to distinguish this client from vanilla RuneLite or the Jagex client, or to statistically separate bot activity from human play.

Findings cite `file:line` against the current `development` branch (commit `3f86a314c3`). All references verified by direct read, not inference.

---

## 1. Threat model

Three categories of observer, with different capabilities:

| Observer | What it sees | Hardest to fool |
|---|---|---|
| **Server-side behavioral** (Jagex gameplay telemetry) | Per-tick position, menu-action id, camera angle, animation, XP curves, chat volume, session cadence. Aggregates over millions of accounts. | Statistical shape of timing/action distributions, path repetition, fatigue curves. |
| **Client-side static** (bytecode/filesystem/window) | JVM classes loaded, files on disk, window title, process name, listening ports. Could be reached from Jagex-shipped code executing in the same process. | Window title, `~/.microbot/`, open localhost port, package names. |
| **Client-side dynamic** (runtime inspection) | `MouseEvent.getSource()`, reflection on `ClassLoader`, stack walks of event dispatch. | `setSource("Microbot")` watermark, `sun.misc.Unsafe` call sites, `MenuOptionClicked` without corresponding mouse motion. |

Static-observer hardening is cheap and delivers the largest immediate risk reduction. Behavioral hardening is more work but addresses the sampling vectors that survive any amount of local obfuscation.

---

## 2. Findings

Grouped by category and severity. Severity reflects **how trivially** a determined observer could detect the signal, not how bad the game-side consequence would be.

### 2.1 Static fingerprints (trivial detection)

| # | Severity | Finding | Evidence |
|---|---|---|---|
| S1 | Critical | `MouseEvent.setSource("Microbot")` tagged on every synthetic mouse event — literal watermark visible to any listener on the AWT event queue. | `runelite-client/.../util/mouse/VirtualMouse.java:150,160,171,185,199,230,236,242,248,254,260` |
| S2 | Critical | Window title is literally `Microbot`. Any JNI-capable observer sampling `GetWindowTextW` / `_NET_WM_NAME` gets the string. | `runelite-client/src/main/resources/net/runelite/client/runelite.properties:1` |
| S3 | Critical | Dedicated config dir `~/.microbot/` with `agent-token` file. Filesystem probe is a one-liner. | `.../microbot/agentserver/AgentServerPlugin.java:164` |
| S4 | Critical | Outbound calls to `microbot.cloud` (version check every ~10 min, random-fact fetch). SNI visible in TLS ClientHello even under HTTPS. | `.../microbot/MicrobotApi.java:27,33`; `.../MicrobotVersionChecker.java:27,182`; `.../RandomFactClient.java:18` |
| S5 | Critical | Agent server binds `127.0.0.1:<port>` (default 8081) with microbot-specific endpoints (`/varp`, `/ground-items`, `/scripts/deploy`, etc.). Port-scan + response shape = instant ID. | `.../microbot/agentserver/AgentServerPlugin.java:87`; `AgentServerConfig.java:21-23` |
| S6 | High | Package namespace `net.runelite.client.plugins.microbot.*` exposed via `ClassLoader.getResources`, `Class.getName`, any loaded-class enumeration. | Pervasive across `runelite-client/src/main/java/net/runelite/client/plugins/microbot/` |
| S7 | High | `microbot.version` / `microbot.commit` baked into `runelite.properties` — trivially readable JAR resource. | `runelite-client/src/main/resources/net/runelite/client/runelite.properties:3-4` |
| S8 | Medium | Hardcoded `User-Agent: Chrome/11.0.696.60` on outbound HTTP — both anomalously old and consistent, which is worse than no UA. | `globval/GlobalConfiguration.java:306-318,327` |
| S9 | Medium | Window title is further mutated at runtime (`"(NEW CLIENT AVAILABLE)"`, break-timer suffixes) — adds distinctive substrings on top of the base leak. | `.../MicrobotVersionChecker.java:120,141`; `.../BreakHandlerScript.java:198` |

### 2.2 Runtime / bytecode signatures

| # | Severity | Finding | Evidence |
|---|---|---|---|
| R1 | High | `sun.misc.Unsafe` used to write to `static final` fields (antiban instrumentation). Vanilla RuneLite does not do this; trivially detected by a bytecode scan inside the JVM. | `.../microbot/util/antiban/FieldUtil.java:3-26` |
| R2 | High | Custom `URLClassLoader` subclass loading `.class` files from `~/.runelite/dynamic-scripts/`. Non-standard classloader chain is enumerable via reflection. | `.../microbot/dynamicscripts/MicrobotPluginClassLoader.java:15`; `DynamicScriptManager.java:73-74` |
| R3 | Medium | ASM (`ClassReader`/`ClassNode`/`MethodNode`) used at runtime to introspect the deobfuscated Client class. Presence of ASM on the classpath plus a runtime ClassReader over `Client.class` is forensically distinctive. | `.../microbot/util/reflection/Rs2Reflection.java:10-12,40-87` |

### 2.3 Input-layer sampling

The input path is what Jagex sees most directly — it arrives on the same AWT queue the real mouse does.

| # | Severity | Finding | Evidence |
|---|---|---|---|
| I1 | Critical | `MenuOptionClicked` events emitted with no preceding mouse motion — classic "click appears out of nowhere" signature. Opcodes set directly via `.opcode(menuAction.getId())` on utility NPC/object/widget interactions. | `.../util/npc/Rs2Npc.java`; `.../util/gameobject/Rs2GameObject.java`; `.../util/widget/Rs2Widget.java`; `.../util/equipment/Rs2Equipment.java`; `.../util/shop/Rs2Shop.java` |
| I2 | High | Natural-mouse click-point generated with a fixed force parameter (`0.78`) — repeated clicks on the same rect show consistent directional offset bias. | `.../util/misc/Rs2UiHelper.java:47-61` |
| I3 | High | Click offset is truncated-Gaussian with a fixed ±10 px range and cutoff=4σ → tight central cluster detectable on a heatmap across a few hundred clicks. | `.../util/mouse/Mouse.java:44`; `.../util/math/Rs2Random.java:124-127,218-231` |
| I4 | High | Overshoot/corrective-move pattern from `DefaultOvershootManager` is deterministic per `ActivityIntensity`; repeated long-distance moves share the same curvature template. | `.../util/mouse/naturalmouse/support/DefaultOvershootManager.java`; `.../FactoryTemplates.java` |
| I5 | High | Camera set via `setCameraYawTarget` / `setCameraPitchTarget` with speed=3 → angle deltas are step ramps, not the smooth acceleration profiles humans produce. | `.../util/camera/Rs2Camera.java:120-142,169` |
| I6 | Medium | Keyboard inter-event gaps sampled from uniform `Rs2Random.between(20,200)` — histogram shape is a rectangle, not a human right-skew. | `.../util/keyboard/Rs2Keyboard.java:76,91,103,114,126,138` |
| I7 | Medium | Scroll events fire on a fixed ~70 ms delay after mouse-move. Narrow variance across samples. | `.../util/mouse/VirtualMouse.java:177-203` |
| I8 | Medium | Click-down / click-up / drag pauses sampled from narrow uniform ranges (`50-80`, `80-120` ms) producing discrete histogram bins. | `.../util/mouse/VirtualMouse.java:276-290` |

### 2.4 Temporal / behavioural sampling

| # | Severity | Finding | Evidence |
|---|---|---|---|
| T1 | Critical | `sleepUntil` and `sleepUntilTrue` both poll at a literal `sleep(100)` — all reactions snap to a 100 ms grid. | `.../util/Global.java:81,115` |
| T2 | Critical | Walker waits baked to 1/2/4-tick multiples (`sleep(600)`, `sleep(1200)`, `sleep(2400)`, etc.). 15+ call sites. Dialogue / transport waits align to the 600 ms game-tick clock. | `.../util/walker/Rs2Walker.java:~1456-3937` (multiple) |
| T3 | High | Default randomness is uniform (`Rs2Random.between` → `nextInt`), not log-normal. Reaction-time distributions come out rectangular. Gaussian helpers exist but callers almost never use them. | `.../util/math/Rs2Random.java:470-487,124-127,381-384,497-511` |
| T4 | High | Antiban action cadence is a sine wave of amplitude ±2 ticks around a hardcoded PlayStyle range. Fourier transform reveals a fixed frequency line. | `.../microbot/util/antiban/PlayStyle.java:62-69,118-134`; `Rs2Antiban.java:263-310` |
| T5 | High | No session-level fatigue modelling — constant actions/hour for the full session. Break durations are uniform over a configured `[min,max]`. | `BreakHandlerV2Script.java` (whole) |
| T6 | Medium | No path diversification — `Rs2Walker` always calls shortest-path. Repeated trips produce pixel-identical server-side tile sequences. | `.../util/walker/Rs2Walker.java:229` |
| T7 | Medium | `PlayStyle` "profile switches" only swap the logging enum; underlying delay/precision do not actually change. | `PlayStyle.java:85-106,145-160` |

### 2.5 In-game behaviour

| # | Severity | Finding | Evidence |
|---|---|---|---|
| B1 | Critical | `STAMINA_ENERGY_THRESHOLD = 30` — every account toggles stamina at exactly the same 30 %. One of the clearest server-side signals. | `.../util/walker/Rs2Walker.java:686,696` |
| B2 | Critical | No handler for trade requests, moderator messages, or random events. An account that never responds to another player is a standing flag. | Absent; searched `ChatMessage`, `tradeRequest`, `modmute`. |
| B3 | High | Input→click latency is whatever the surrounding loop produces — often tens of ms. Human reaction median ≈ 250 ms. | `.../microbot/Microbot.java` (`doInvoke`) plus most plugins. |
| B4 | High | Camera rotation is *reactive* (right before an invoke), never *anticipatory* (humans rotate before deciding to act). | `.../util/npc/Rs2Npc.java:64` (`Rs2Camera.turnTo`) |
| B5 | Medium | Inventory item selection always picks the closest slot via Manhattan distance — humans have slot-bias from muscle memory but nontrivial scatter. | `.../util/inventory/Rs2Inventory.java:184-200` |
| B6 | Medium | Auto-login retry fires every 600 ms. A human notices a disconnect in 30-120 s. | `.../accountselector/AutoLoginScript.java:54-72,80-95` |
| B7 | Medium | No outbound chat. 50 h of play with zero messages is a trivial server-side filter. | Absent; `ChatMessage` used only for reads. |

---

## 3. Remediation plan

Ordered by detection-reduction per unit effort. Each item lists the highest-value findings it addresses.

### Phase 1 — drop the obvious watermarks (low effort, high impact)

P1-a. **Strip the `setSource("Microbot")` watermark.** Either leave source null (matches synthetic events from other injectors) or set it to a string that occurs in vanilla RuneLite code paths. One file, 11 call sites.
— addresses **S1**.

P1-b. **Change `runelite.title` to `RuneLite`** (or the upstream value) and stop mutating it at runtime for update banners / break-timer text. Surface those in the side panel or a titled overlay instead of the OS window title.
— addresses **S2, S9**.

P1-c. **Move config dir off `~/.microbot/`.** Options, in order of preference: (i) fold everything under `~/.runelite/microbot/` so the outer directory is indistinguishable from vanilla; (ii) honour an env var for the path. Keep `agent-token` file permissions (0600).
— addresses **S3**.

P1-d. **Strip the Chrome-11 `User-Agent`.** Use the default OkHttp/`HttpURLConnection` UA, or — where a UA is required — match the vanilla RuneLite user-agent exactly.
— addresses **S8**.

P1-e. **Strip or rename `microbot.version` / `microbot.commit`** in `runelite.properties`. Version info is useful; it does not need to be resource-discoverable by that name. Prefer a non-resource constant, or merge into an existing RuneLite property with a neutral name.
— addresses **S7**.

### Phase 2 — retire the `microbot.cloud` SNI signal

P2-a. **Make the version-check and random-fact endpoints configurable**, with the ability to disable them. Even with SNI, user-controlled DNS avoids a fixed signature tied to the client build.

P2-b. **Backoff the version-check cadence** (opportunistic on launch only, plus manual refresh) rather than every 10 minutes — the periodic heartbeat is what turns it into a tracking signal.
— addresses **S4**.

### Phase 3 — agent server surface reduction

P3-a. **Off-by-default with explicit opt-in** (already the case — keep it) *and* **randomise the default port** per install, stored in the user config, so fingerprinting by port-number alone fails. Document the port in the side panel, not in a public default.

P3-b. **Add an optional "stealth mode"** that only binds the agent server while a script is actively running, and only to a UNIX domain socket where the OS supports it (falling back to ephemeral TCP otherwise). This eliminates the port listener when the user is playing manually.

P3-c. **Neutral endpoint names / response shapes.** The current `/varp`, `/ground-items`, `/scripts/deploy` set is a signature by itself. At minimum, gate the entire router behind the `X-Agent-Token` header with no unauthenticated discovery path (currently the 401 responses may still leak shape).
— addresses **S5**.

### Phase 4 — input-layer humanisation

P4-a. **Require a mouse trajectory for every menu-option click.** Introduce a `performClick(MenuEntry)` primitive that runs a real natural-mouse motion to the entry's `getMenuTarget()` bounds first, then dispatches the click. Deprecate the direct-opcode helpers in `Rs2Npc`, `Rs2GameObject`, `Rs2Widget`, `Rs2Equipment`, `Rs2Shop` — `MenuOptionClicked` without a matching mouse path is the single biggest input-layer tell.
— addresses **I1, B3, B5**.

P4-b. **Replace uniform reaction-time RNG with a log-normal primitive.** Add `Rs2Random.reactionTime()` that samples log-normal with human-fit parameters (median ≈ 260 ms, σ ≈ 0.3), and replace `between(lo, hi)` at reaction sites with it. Keep `between` for range-constrained waits, but audit callers.
— addresses **T3, I6, I8**.

P4-c. **Randomise the click-point force and radius.** Make the `0.78` force parameter a per-session drawn value, and vary the click-offset radius per widget class (tight for small icons, loose for large canvases). Remove the fixed ±10 px box.
— addresses **I2, I3**.

P4-d. **Smooth camera.** Replace direct yaw/pitch setters with a motion that interpolates over a realistic duration (200-800 ms) with an ease-in-out profile. Add anticipatory rotation — kick off camera movement before the interaction decision is finalised.
— addresses **I5, B4**.

### Phase 5 — temporal de-quantisation

P5-a. **Jitter the `sleepUntil` poll interval.** Change the fixed `sleep(100)` in `Global.java:81,115` to a sampled interval (e.g. log-normal around 80-130 ms, re-sampled each iteration). One-line change, eliminates the 100 ms grid.
— addresses **T1**.

P5-b. **Un-align tick waits.** Introduce a `tickWait(n)` helper that returns `n*600 ± jitter_lognormal(80)` and replace literal `sleep(600*k)` sites in `Rs2Walker`. The action will still resolve on the same tick; the client's wake-up will not.
— addresses **T2**.

P5-c. **De-periodicise antiban.** Replace the sine-wave amplitude in `PlayStyle.evolvePlayStyle` with a brown-noise / OU-process jitter so Fourier analysis does not find a line. Make the inter-antiban cadence itself sampled log-normal rather than bounded uniform.
— addresses **T4, T7**.

P5-d. **Session fatigue.** Add a session-scoped multiplier on action delays that drifts upward over the session (e.g. +1 % per 15 minutes) and resets after breaks. Change break durations to a bimodal distribution: short (≤ 2 min) with high weight, long (30-120 min) with small weight.
— addresses **T5**.

### Phase 6 — behavioural coverage

P6-a. **Per-account stamina threshold.** Draw `STAMINA_ENERGY_THRESHOLD` once per account (20-55 %) and persist it, so repeated play from one account is consistent but population-wide it spreads.
— addresses **B1**.

P6-b. **Random-event / trade / mod-mute handlers.** Register listeners that pause the running script, perform a plausible response (decline trade after 10-40 s, dismiss random event through its own dialogue UI), and log the intervention for the user.
— addresses **B2**.

P6-c. **Auto-login backoff.** Replace the 600 ms retry with exponential backoff (5 s → 15 s → 60 s → 5 min) plus jitter, and require a minimum 5-second idle between disconnect detection and the first retry.
— addresses **B6**.

P6-d. **Optional chat trickle.** Opt-in emote / occasional-reply feature so long-running accounts produce *some* outbound chat. Off by default; users turn it on if they care about the signal.
— addresses **B7**.

### Phase 7 — runtime surface (lower priority)

P7-a. **Replace `sun.misc.Unsafe`** in `FieldUtil` with `VarHandle`+`setAccessible` or a plain reflective setter where possible. `Unsafe` is more forensically distinctive than accessible-reflection on the same fields.
— addresses **R1**.

P7-b. **Keep ASM runtime use localised**, or move it into build-time code generation where the result is just reflection at runtime. Harder but removes a classpath signature.
— addresses **R3**.

P7-c. **Dynamic-script classloader** — lower priority; `URLClassLoader` is common, the *name* `MicrobotPluginClassLoader` is the only giveaway. Rename.
— addresses **R2**.

---

## 4. Explicitly out of scope

- **Package renaming / ProGuard.** The `net.runelite.client.plugins.microbot.*` namespace is pervasive and project-defining. Renaming it is a separate, larger piece of work and is a weaker signal than items S1-S5 anyway.
- **Game-packet manipulation.** Nothing in `net.runelite.client.plugins.microbot.*` writes to the wire directly; all packet interaction goes through vanilla RuneLite hooks. No action needed.
- **Remote script loading.** `DynamicScriptManager` only loads from local disk; no remote class-fetch path exists. Not a current risk.
