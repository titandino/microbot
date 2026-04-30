# The Nightmare / Phosani's Nightmare — Implementation Reference

Comprehensive, plugin-author-oriented reference for implementing a Nightmare helper or
automation script in this Microbot fork. All IDs are pulled from
`runelite-api/src/main/java/net/runelite/api/gameval/{NpcID,AnimationID,SpotanimID,ObjectID}.java`
in this tree (i.e. they are correct as of the cache version vendored here, not scraped
from a fan wiki).

This document does **not** ship a working bot. It captures the cheaty information that
historical "helper" plugins encoded — what to pray, when to react, what to look for,
where to walk — so that a future Kotlin plugin under `trent/nightmare/` can be authored
without re-deriving any of it.

## Sources translated

| Source | URL | What was used |
|---|---|---|
| OpenOSRS PR #2314 (`net.runelite.client.plugins.nightmare`) by ASwatek | https://github.com/open-osrs/runelite/pull/2314 | Animation→prayer enum, totem-phase enum, tick-counter logic, curse handling, region & NPC IDs, prayer overlay |
| JRPlugins / Piggy NightmareHelper (`com.example.NightmareHelper`) by Jrod7938 | https://github.com/Jrod7938/JRPlugins | Auto-prayer toggle pattern, weapon-style/cursed inversion, region 15258 detection |
| OSRS Wiki — The Nightmare / Strategies | https://oldschool.runescape.wiki/w/The_Nightmare/Strategies | Mechanic descriptions, tick reaction windows, scaling, gear, supplies |
| OSRS Wiki — Phosani's Nightmare / Strategies | https://oldschool.runescape.wiki/w/Phosani%27s_Nightmare/Strategies | Phosani-specific phase layout, special-attack rotation sets (A1/A2/B1/B2), corner-rifting, sleepwalker damage table, parasite one-hit thresholds |
| OSRS Wiki — Sleepwalker, Husk, Parasite, Totem subpages | various | Per-entity stats, drop / behavior details |
| RuneLite gameval headers (this repo) | `runelite-api/src/main/java/net/runelite/api/gameval/*.java` | Authoritative numeric IDs |

The two helper plugins above are the closest historical analogues to a "show me what to
pray" / "auto-flick" Nightmare overlay. PR #2314 was closed unmerged on 2020-02-11 with
"due to the rework, and our stance of going compliant, this will not be something we
add" — but the source files exist on the branch ref `29b2a6b` and are reproduced in
section _Translated plugin logic_ below.

## The encounter at a glance

Two flavours share most code paths but differ in scaling and one-extra-phase:

- **The Nightmare** — group boss in the Sisterhood Sanctuary (Slepe). 1–80 players.
  Three phases. Boss has 2,400 HP; each phase ends when the totems unleash an 800-dmg
  blast. Phase mechanics: P1 grasping-claws + husks + corpse-flowers; P2 sleepwalkers
  + curse + parasites; P3 surge + spores + (P3 is the burn-down).
- **Phosani's Nightmare** ("Challenge" mode in cache strings) — solo only, harder.
  Four phases: 3 standard (400 shield → 200/100 totem charge per phase) + a
  desperation phase (150 HP). Boss awakens in 10 s vs. 30 s. Reclaim cost 60,000 gp.

Both fights share the same arena (region **15256**), the same totem-charge mechanic,
and almost all of the same animation/projectile IDs. Phosani's uses a parallel set of
`NIGHTMARE_CHALLENGE_*` NPC IDs (e.g. `9416/9417/9418/11153/11154` instead of
`9425/9426/9427`).

### Arena layout

- 5×5 boss footprint in the centre.
- Four totems, one in each corner of the arena (NW / NE / SW / SE). Each totem has a
  single fixed tile — when the boss enters a phase, the totems in that corner spawn
  fresh as the *dormant* NPC variant. There are four totem "slots" and the cache
  exposes them as four ID triples (`TOTEM_1_*` … `TOTEM_4_*`); the slot index does
  not encode which corner.
- Sleepwalkers spawn from the four corners and walk toward the boss. In Phosani Phase
  3 the player should stand near a corner so that one of the four sleepwalkers spawns
  in melee range of the player.
- Boss sleeps initially; she wakes up on engagement (10 s Phosani / 30 s group). The
  initial sleeping NPC is `NIGHTMARE_INITIAL = 9432` / `NIGHTMARE_CHALLENGE_INITIAL = 9423`.

### Phase progression (NPC ID flow)

For The Nightmare:

```
9432  (NIGHTMARE_INITIAL, sleeping)
  → 9425 (PHASE_01)              ─┐
    → 9428 (WEAK_PHASE_01)        │ shield broken; totems attackable
       → 9431 (BLAST)             │ totems fire, deals 800 to boss
    → 9426 (PHASE_02)             │
    → 9429 (WEAK_PHASE_02)        │
       → 9431 (BLAST)             │
    → 9427 (PHASE_03)             │
    → 9430 (WEAK_PHASE_03)        │
       → 9431 (BLAST)            ─┘
  → 9433 (DYING)
  → 378  (DEAD)
```

For Phosani's Nightmare swap the prefix to `NIGHTMARE_CHALLENGE_*` and use
`9416/9417/9418/11153 (PHASE_04 desperation)/9419/9420/9421/11155 (WEAK_04)/9422 (BLAST)`
+ initial `9423` + dying `9424` + dead `377`.

The "fight has begun" trigger that the OpenOSRS plugin keys off is **NPC definition
change to ID 9432** (or 9423 for Phosani) — that is the moment the sleeping boss is
replaced by the active phase-1 NPC.

## Attack identification — the core cheat

### Standard 3-style rotation

The boss attacks every **7 ticks (4.2 s)**. The animation that plays *is* the tell
— it fires the same tick the projectile/hit is launched, and the player has the
remaining ticks of the cycle (3 ticks for ranged/magic, 2 ticks for melee per the
wiki's reaction-window numbers) to swap protect prayers.

Authoritative IDs (`runelite-api/.../gameval/AnimationID.java`):

| Animation | ID | Pray |
|---|---|---|
| `NIGHTMARE_ATTACK_MELEE` | **8594** | Protect from Melee |
| `NIGHTMARE_ATTACK_MAGIC` | **8595** | Protect from Magic |
| `NIGHTMARE_ATTACK_RANGED` | **8596** | Protect from Missiles |

Sprite IDs used by the OpenOSRS overlay (drawn in the BR corner):

| Style | Sprite |
|---|---|
| Magic | 127 |
| Missiles | 128 |
| Melee | 129 |

Wiki-described tells (in case you ever need to recognise the attack from sound/visual
without reading the animation ID):

- **Ranged** — boss contorts limbs into unnatural shapes, three loud clicks, then fires.
- **Magic** — flails to one side surrounded by pink flower petals, red flecks around hands.
- **Melee** — screech + claw drag from behind to in front. Hits everything in front
  of her *and* the tiles diagonally adjacent and directly under her. Off-tank
  positions avoid this entirely.

### Curse / Confusion (the inversion)

Animation **`NIGHTMARE_ATTACK_CONFUSION = 8599`** triggers the curse special. While
cursed, the protect-prayer mapping shifts one slot to the right (i.e. clicking
"Protect from Magic" activates "Protect from Missiles", etc.). The cursed mapping
the plugin should apply:

| Animation | Normal pray | **Cursed** pray |
|---|---|---|
| 8594 (melee) | Melee | **Missiles** |
| 8595 (magic) | Magic | **Melee** |
| 8596 (ranged) | Missiles | **Magic** |

Curse lasts **5 standard attacks**, then drops. The chat lines used to enter / exit
curse in the OpenOSRS plugin:

```
"The Nightmare has cursed you, shuffling your prayers!"   // enter
"You feel the effects of The Nightmare's curse wear off." // exit
```

(There is also a curse-exit broadcast from the boss "yawn" — the safer signal is the
counter `attacksSinceCurse`: increment on every standard attack while `cursed=true`,
clear `cursed` and reset counter when it hits 5.)

### Reaction window

The OpenOSRS plugin sets `ticksUntilNextAttack = 7` on every standard-attack animation
and decrements on `onGameTick`. When it reaches 0 the next attack is cleared and the
overlay disappears. While `>= 4` it draws the next attack's tick-color (red/cyan/green);
otherwise it shows white (you've passed the safe-swap window).

## Special attacks — full ID + behaviour reference

Pulled from `gameval/AnimationID.java` (these are the actual cache strings, so don't
re-guess names from the OpenOSRS plugin's older constants):

| Cache name | ID | Meaning |
|---|---|---|
| `NIGHTMARE_ATTACK_MELEE` | 8594 | Standard melee |
| `NIGHTMARE_ATTACK_MAGIC` | 8595 | Standard magic |
| `NIGHTMARE_ATTACK_RANGED` | 8596 | Standard ranged |
| `NIGHTMARE_ATTACK_SURGE` | 8597 | Charge teleport (P3 only) — wind-up |
| `NIGHTMARE_ATTACK_RIFT` | 8598 | Grasping-Claws / shadow portals spawn |
| `NIGHTMARE_ATTACK_RIFT_FAST` | 8971 | Faster rift variant (Phosani / desperation) |
| `NIGHTMARE_ATTACK_RIFT_PHASE_04_START` | 9102 | Desperation rift wind-up |
| `NIGHTMARE_ATTACK_RIFT_PHASE_04_IDLE` | 9103 | Desperation rift loop |
| `NIGHTMARE_ATTACK_RIFT_PHASE_04_FINISH` | 9104 | Desperation rift end |
| `NIGHTMARE_ATTACK_CONFUSION` | 8599 | Curse — shuffle prayers |
| `NIGHTMARE_ATTACK_INFECTION` | 8600 | Spawn corpse-flower mushrooms |
| `NIGHTMARE_ATTACK_SEGMENT` | 8601 | Quadrant flowers (red/white) split |
| `NIGHTMARE_ATTACK_SUMMON` | 8602 | Summon husks |
| `NIGHTMARE_ATTACK_CHARGE` | 8603 | Charge wind-up (post-teleport surge) |
| `NIGHTMARE_ATTACK_BLAST` | 8604 | Sleepwalker power-blast (sleep damage) |
| `NIGHTMARE_ATTACK_TRANCE` | 8605 | Sleepwalker spawn / parasite toss prelude |
| `NIGHTMARE_ATTACK_PARASITE` | 8606 | Parasite toss |
| `NIGHTMARE_DESPAWN` | 8607 | Boss vanishes (between phases) |
| `NIGHTMARE_ATTACK_BLAST_DESPAWN` | 8608 | Blast aftermath |
| `NIGHTMARE_RESPAWN` | 8609 | Boss reappears next phase |
| `NIGHTMARE_ATTACK_BLAST_RESPAWN` | 8610 | Blast aftermath part 2 |
| `NIGHTMARE_SPAWN_INITIAL` | 8611 | First wake-up |
| `NIGHTMARE_DEATH` | 8612 | Final death |

### 1. Grasping Claws / Rift portals

- Animation: **8598** (`NIGHTMARE_ATTACK_RIFT`), or **8971** (fast variant).
- Visual: shadow tiles spawn under players. The cache exposes the shadow as a
  `GraphicsObject` with id **`NIGHTMARE_RIFT = 1767`** in `gameval/SpotanimID.java`.
  This is the same ID the OpenOSRS overlay highlights orange.
- Damage on the tile: **50** in The Nightmare, **55–65** in Phosani's.
- "Corner rifting": stand on the boss-corner tile when the rift fires; the portals
  spawn under the player's previous tile, so step off next tick. In Phosani's
  desperation phase this is the survival technique — rift fires repeatedly while
  sleepwalkers stream in.
- Implementation: subscribe to `onGraphicsObjectCreated` (or `client.getGraphicsObjects()`
  inside the overlay render) and highlight any object whose id is **1767**. The
  shadow lasts ~3 ticks; mark the tile as a damaging tile during that window.

### 2. Charge / Surge (Phase 3 only on group, P3+desperation Phosani)

- Wind-up animation: **8597** (`NIGHTMARE_ATTACK_SURGE`).
- Boss teleports to one edge of the arena, then surges across to the opposite side.
  Up to **60 dmg** group / **80 dmg** Phosani.
- Avoidable by stepping out of the line.
- The teleport itself uses spotanim **`NIGHTMARE_TELEPORT = 1763`**.

### 3. Curse (Phase 2 group, all phases possible Phosani A1/B1)

- Animation: **8599** (`NIGHTMARE_ATTACK_CONFUSION`).
- Travel spotanim: **`NIGHTMARE_TRANCE_TRAVEL = 1781`**.
- Inverts protect prayers for **5 attacks**.
- See _Curse / Confusion_ section above.

### 4. Husks (Phase 1 group, A1/A2 Phosani)

- Spawn animation on the boss: **8602** (`NIGHTMARE_ATTACK_SUMMON`).
- Two husks spawn:
  - `NIGHTMARE_HUSK_MAGIC = 9454` (blue, skinny — uses magic). Phosani: `9466`.
  - `NIGHTMARE_HUSK_RANGED = 9455` (green, bulky — uses ranged). Phosani: `9467`.
- The targeted player is **frozen** until the husks are killed. Husks have **20 HP**
  each (Phosani's hits much harder than group's despite same HP).
- Husk attack animations:
  - `HUSK_RANGED_ATTACK = 8564`
  - `HUSK_MAGIC_ATTACK = 8565`
- Husk projectile spotanims:
  - `NIGHTMARE_HUSK_MAGIC_TRAVEL = 1776`, impact `1777`
  - `NIGHTMARE_HUSK_RANGED_TRAVEL = 1778`
- **Strategy**: kill the bulkier ranged husk first. The boss does **not** spawn
  rift portals under frozen players, so during a husk lock the player can ignore
  rift mechanics. Crush weapons (e.g. Inquisitor's mace, Scythe) get max-hit roll
  against husks per the wiki.

### 5. Parasite (Phase 2 group, B1/B2 Phosani)

- Toss animation on the boss: **8606** (`NIGHTMARE_ATTACK_PARASITE`).
- Throw spotanim (the projectile arc): **`NIGHTMARE_PARASITE_TRAVEL = 1770`**.
- Impregnated player NPC: `NIGHTMARE_PARASITE = 9452` (regular) / `9468` (Phosani) once
  it pops out, with weak variant `9453` / `9469`. The parasite itself is a real NPC
  the player must kill; it has its own animations:

| Animation | ID |
|---|---|
| `NIGHTMARE_PARASITE_IDLE` | 8553 |
| `NIGHTMARE_PARASITE_ATTACK_RANGED` | 8554 |
| `NIGHTMARE_PARASITE_ATTACK_MAGIC` | 8555 |
| `NIGHTMARE_PARASITE_IMPREGNATE_PLAYER` | 8556 |
| `NIGHTMARE_PARASITE_VOMIT` | 8557 |
| `NIGHTMARE_PARASITE_VOMIT_SPOTANIM` | 8558 |
| `NIGHTMARE_PARASITE_DEATH` | 8559 |
| `NIGHTMARE_PARASITE_SPAWN` | 8560 |
| `NIGHTMARE_PARASITE_STRONG` | 8561 |

- Phosani timing: **~18 ticks (10.8 s)** between impregnation and burst.
- Drinking **Sanfew serum** or **Relicym's balm** before the burst weakens the
  parasite (NPC id changes from 9452 → 9453, 9468 → 9469). A weak parasite takes
  max-hit from a crush weapon. Per wiki: **118 Strength + ≥160 strength bonus + Piety**
  guarantees a one-hit on the weakened parasite.
- Implementation hints:
  - Watch for the toss anim 8606 + spotanim 1770 to know "someone got infected".
  - When the player's own animation matches `NIGHTMARE_PARASITE_IMPREGNATE_PLAYER`
    (8556), start an internal 18-tick countdown.
  - Highlight the parasite NPC after it spawns; auto-drink Sanfew within the window
    if the player isn't in danger of overkilling the cycle.

### 6. Sleepwalkers (Phase 2 group between-phases, P1–desperation Phosani)

- Boss animations:
  - `NIGHTMARE_ATTACK_TRANCE = 8605` — spawn cue.
  - `NIGHTMARE_ATTACK_BLAST = 8604` — power blast (the "you missed sleepwalkers" hit).
- Sleepwalker NPC IDs:
  - Group: `NIGHTMARE_SLEEPWALKER_1..6 = 9446..9451` (six distinct skins, used to
    distribute spawns visually). All behave identically.
  - Phosani: single id `NIGHTMARE_CHALLENGE_SLEEPWALKER = 9470`.
  - Pre-fight Slepe-area ambient sleepwalkers: `NIGHTMARE_SLEEPWALKER_PRE1..PRE4 =
    1029..1032` and `9801/9802` for Canifis variants. Filter these out — they are
    not part of the boss fight.
- Sleepwalker animations: `SLEEPWALKER_WALK = 8534`, `IDLE = 8568`, `READY = 8569`,
  `DEATH = 8570`, `ABSORB = 8571`, `SPAWN = 8572`.
- **HP = 10**, dies to any damage (any hit, any weapon — even an unarmed punch).
- Phosani spawn counts: **2 / 3 / 4 / continuous** at end of P1 / P2 / P3 / desperation.
- Phosani damage table (sleepwalkers leaked → damage taken from blast):

| Spawned | 0 leak | 1 | 2 | 3 | 4 |
|---|---|---|---|---|---|
| 2 | 5 | 77 | 149 | — | — |
| 3 | 5 | 53 | 101 | 149 | — |
| 4 | 5 | 41 | 77 | 113 | 149 |

  Same formula on the wiki: `damage = 5 + 144 * leaked / total`.

- **Phase-3 corner trick (Phosani)**: stand within melee of a corner so that one of
  the four spawns appears adjacent. Kill the closer spawn first (it has the shortest
  walk distance, so will leak first if ignored). The wiki: "the player must subdue the
  second sleepwalker before they start moving, or one of them will always leak onto
  the Nightmare and deal 41 damage."
- Recommended weapons (wiki): long-range, fast attack speed, fast projectile —
  Webweaver bow, Craw's bow, Toxic blowpipe, Eye of Ayak. The fast travel time matters
  because spawns are at the four corners simultaneously.

### 7. Spores / puffshrooms (Phase 3 group; A1/A2 Phosani)

- Animation: **8600** (`NIGHTMARE_ATTACK_INFECTION`).
- Mushroom GameObject id: **37739** (per the OpenOSRS overlay constant
  `NIGHTMARE_MUSHROOM`).
- Walking within **1 tile** of a mushroom triggers a yawn — disables run, lowers
  attack speed by **2 ticks** (Phosani; group is 1 tick), drains **6 prayer per yawn**.
- Wiki: spores accompany Grasping Claws when both are part of the rotation set.

### 8. Corpse Flowers / Quadrant flowers (Phase 1 group; B1/B2 Phosani)

- Animation: **8601** (`NIGHTMARE_ATTACK_SEGMENT`).
- Boss divides the arena into four quadrants. Three are filled with **red nightmare
  berries** (deals up to ~20 dmg/tick, **also heals the boss for 2× the damage
  dealt**); one quadrant has **white nightmare flowers** — that's the safe quadrant.
- The split spotanim is `NIGHTMARE_FLIES = 1783` (cosmetic flies above the safe tiles
  per cache labelling).
- Implementation: detect anim 8601, then read the flowers in the arena to identify
  which of the four quadrants is safe; project a polygon for the safe area on the
  overlay.

## Totem mechanics

Totem state cycle:

```
DORMANT (idle, ignored)
  → READY (shield broken; attackable; charge accumulates as players hit it)
  → CHARGED (300+ pts, max 2,640; ready to fire blast on the boss)
  → DORMANT (next phase resets)
```

The four totems use four ID triples (`runelite-api/.../gameval/NpcID.java`):

| Slot | Dormant | Ready | Charged |
|---|---|---|---|
| 1 | 9434 | 9435 | 9436 |
| 2 | 9437 | 9438 | 9439 |
| 3 | 9440 | 9441 | 9442 |
| 4 | 9443 | 9444 | 9445 |

The OpenOSRS `TotemPhase` enum maps every concrete ID to a colour: orange = dormant,
green = ready/active, red = charged. The plugin tracks each totem in a
`MemorizedTotem(NPC, TotemPhase)` and re-enters the enum on `onNpcDefinitionChanged`
via `TotemPhase.valueOf("TOTEM_" + newId)`.

### Charge thresholds (wiki)

- **Group**: minimum 300 charge points to fire, capping at 2,640. +30 per player in
  6+ player encounters.
- **Phosani's solo**: 200 charge per phase, **100 if maging** (magic deals 2× damage
  to totems). 3 totem-charge phases × 4 = 12 totems total in the fight.
- **Magic deals 2× damage to totems**, so a magic spec'd inventory will dispatch
  totems much faster.
- **Totems cannot be leeched** with blood spells (the heal is suppressed).
- Uncharged totems have **70% fire weakness**; charged totems have no weakness.

When all four totems are charged, they unleash a magic blast (animation
`NIGHTMARE_ATTACK_BLAST = 8604` mirrored, plus spotanim
`NIGHTMARE_TOTEM_FULLY_CHARGED = 8589` / `NIGHTMARE_GARDEN_TOTEM_SPELL_IMPACT = 1769`).
Damage = 800 to the boss.

### Detecting "shield is broken" / phase transitions

Two reliable signals:

1. **NPC composition change**: subscribe to `onNpcDefinitionChanged` and watch the
   boss NPC. Transition `9425 → 9428` = phase-1 shield broken; `9428 → 9426` = phase
   2 has begun.
2. **Totem activation**: when the four totems' NPC ids transition from `*_DORMANT`
   → `*_READY` (e.g. 9434 → 9435), the shield phase is over. The OpenOSRS plugin
   uses this as the trigger to start tracking totems.

## Phosani's Nightmare specifics

### Phase rotation sets

Each phase rolls one of four "sets" of three special attacks. The first phase is
random A or B, subsequent phases alternate A↔B but re-randomise the 1↔2 within:

| Set | Specials |
|---|---|
| **A1** | Spores + Husks + Curse |
| **A2** | Spores + Husks + Surge |
| **B1** | Flowers + Parasite + Curse |
| **B2** | Flowers + Parasite + Surge |

Detection in code: log the first three `NIGHTMARE_ATTACK_*` non-standard anims in a
phase to identify which set the player is in, then pre-warn the third special before
it lands.

### Phase 3 prayer deactivation

Unique to Phosani Phase 3: the boss occasionally "deactivates the player's protection
prayer if they have the correct prayer active before the attack animation starts."
This is a hard-counter to lazy auto-flicking — you must re-toggle prayer *after* the
boss's standard-attack animation has begun (i.e. after the 8594/8595/8596 anim event,
not before). For an auto-prayer plugin this means: don't toggle prayer pre-emptively
ahead of the next expected attack tick during P3.

### Desperation phase (P4)

- Animations: `NIGHTMARE_ATTACK_RIFT_PHASE_04_START/IDLE/FINISH = 9102/9103/9104`,
  `NIGHTMARE_ATTACK_RIFT_FAST = 8971`.
- 150 HP boss; rift fires continuously while sleepwalkers stream in indefinitely.
- Survive via corner rifting + spec weapons. Wiki order: **Voidwaker > Dragon claws >
  Granite maul (ornate) > Saradomin godsword**. Redemption prayer recommended for
  extended phases.

## Authoritative ID tables (in this repo)

These were grepped from `runelite-api/src/main/java/net/runelite/api/gameval/`. Use
the `gameval` constants when writing the plugin — the older `NpcID.NIGHTMARE_*` /
`AnimationID.NIGHTMARE_*` in `runelite-api/.../api/{NpcID,AnimationID}.java` are kept
for backwards compatibility and may not have all of these.

### `gameval.NpcID`

```
NIGHTMARE_INITIAL              = 9432    // sleeping, not engaged
NIGHTMARE_PHASE_01             = 9425
NIGHTMARE_PHASE_02             = 9426
NIGHTMARE_PHASE_03             = 9427
NIGHTMARE_WEAK_PHASE_01        = 9428    // shield broken
NIGHTMARE_WEAK_PHASE_02        = 9429
NIGHTMARE_WEAK_PHASE_03        = 9430
NIGHTMARE_BLAST                = 9431    // post-totem-blast form
NIGHTMARE_DYING                = 9433
NIGHTMARE_DEAD                 = 378

// Phosani's "Challenge" parallel set
NIGHTMARE_CHALLENGE_INITIAL    = 9423
NIGHTMARE_CHALLENGE_PHASE_01   = 9416
NIGHTMARE_CHALLENGE_PHASE_02   = 9417
NIGHTMARE_CHALLENGE_PHASE_03   = 9418
NIGHTMARE_CHALLENGE_PHASE_04   = 11153   // desperation
NIGHTMARE_CHALLENGE_PHASE_05   = 11154
NIGHTMARE_CHALLENGE_WEAK_PHASE_01 = 9419
NIGHTMARE_CHALLENGE_WEAK_PHASE_02 = 9420
NIGHTMARE_CHALLENGE_WEAK_PHASE_03 = 9421
NIGHTMARE_CHALLENGE_WEAK_PHASE_04 = 11155
NIGHTMARE_CHALLENGE_BLAST      = 9422
NIGHTMARE_CHALLENGE_DYING      = 9424
NIGHTMARE_CHALLENGE_DEAD       = 377

// Totems (4 slots × {dormant, ready, charged})
NIGHTMARE_TOTEM_1_DORMANT/READY/CHARGED = 9434/9435/9436
NIGHTMARE_TOTEM_2_DORMANT/READY/CHARGED = 9437/9438/9439
NIGHTMARE_TOTEM_3_DORMANT/READY/CHARGED = 9440/9441/9442
NIGHTMARE_TOTEM_4_DORMANT/READY/CHARGED = 9443/9444/9445

// Adds — group fight
NIGHTMARE_SLEEPWALKER_1..6  = 9446..9451   // skin variants
NIGHTMARE_PARASITE          = 9452
NIGHTMARE_PARASITE_WEAK     = 9453
NIGHTMARE_HUSK_MAGIC        = 9454
NIGHTMARE_HUSK_RANGED       = 9455
NIGHTMARE_OUTER_GHOST       = 9458         // ambient outside arena

// Adds — Phosani's
NIGHTMARE_CHALLENGE_HUSK_MAGIC   = 9466
NIGHTMARE_CHALLENGE_HUSK_RANGED  = 9467
NIGHTMARE_CHALLENGE_PARASITE     = 9468
NIGHTMARE_CHALLENGE_PARASITE_WEAK= 9469
NIGHTMARE_CHALLENGE_SLEEPWALKER  = 9470
NIGHTMARE_CHALLENGE_SISTER_1OP   = 9471
NIGHTMARE_CHALLENGE_SISTER_2OP   = 9472
NIGHTMARE_CHALLENGE_SISTER       = 9473
NIGHTMARE_SLEEPWALKER_CONTROL    = 9800

// Entry archway (the "queue/portal" UI before the fight)
NIGHTMARE_ENTRY_READY     = 9460
NIGHTMARE_ENTRY_OPEN      = 9461
NIGHTMARE_ENTRY_CLOSED_01 = 9462
NIGHTMARE_ENTRY_CLOSED_02 = 9463
NIGHTMARE_ENTRY_CLOSED_03 = 9464

// Pre-fight ambient (filter these out of "is sleepwalker?" checks)
NIGHTMARE_SLEEPWALKER_PRE1..PRE4 = 1029..1032
NIGHTMARE_SLEEPWALKER_PRE3_CANIFIS = 9801
NIGHTMARE_SLEEPWALKER_PRE4_CANIFIS = 9802

// Pets
NIGHTMARE_PET           = 9399
POH_NIGHTMARE_PET       = 9398
NIGHTMARE_PET_PARASITE  = 8541
POH_NIGHTMARE_PET_PARASITE = 8183
```

### `gameval.AnimationID`

```
// Boss
NIGHTMARE_HEAD_LOC                = 8591   // idle pose
NIGHTMARE_WALK                    = 8592
NIGHTMARE_IDLE                    = 8593
NIGHTMARE_ATTACK_MELEE            = 8594
NIGHTMARE_ATTACK_MAGIC            = 8595
NIGHTMARE_ATTACK_RANGED           = 8596
NIGHTMARE_ATTACK_SURGE            = 8597
NIGHTMARE_ATTACK_RIFT             = 8598
NIGHTMARE_ATTACK_CONFUSION        = 8599
NIGHTMARE_ATTACK_INFECTION        = 8600
NIGHTMARE_ATTACK_SEGMENT          = 8601
NIGHTMARE_ATTACK_SUMMON           = 8602
NIGHTMARE_ATTACK_CHARGE           = 8603
NIGHTMARE_ATTACK_BLAST            = 8604
NIGHTMARE_ATTACK_TRANCE           = 8605
NIGHTMARE_ATTACK_PARASITE         = 8606
NIGHTMARE_DESPAWN                 = 8607
NIGHTMARE_ATTACK_BLAST_DESPAWN    = 8608
NIGHTMARE_RESPAWN                 = 8609
NIGHTMARE_ATTACK_BLAST_RESPAWN    = 8610
NIGHTMARE_SPAWN_INITIAL           = 8611
NIGHTMARE_DEATH                   = 8612
NIGHTMARE_ATTACK_RIFT_FAST        = 8971   // Phosani / desperation
NIGHTMARE_ATTACK_RIFT_PHASE_04_START  = 9102
NIGHTMARE_ATTACK_RIFT_PHASE_04_IDLE   = 9103
NIGHTMARE_ATTACK_RIFT_PHASE_04_FINISH = 9104

// Totems
NIGHTMARE_GARDEN_TOTEM_SPELL          = 8587
NIGHTMARE_GARDEN_TOTEM_SPELL_IMPACT   = 8588
NIGHTMARE_TOTEM_FULLY_CHARGED         = 8589

// Husks
HUSK_RANGED_ATTACK = 8564
HUSK_MAGIC_ATTACK  = 8565

// Sleepwalkers
SLEEPWALKER_WALK   = 8534
SLEEPWALKER_IDLE   = 8568
SLEEPWALKER_READY  = 8569
SLEEPWALKER_DEATH  = 8570
SLEEPWALKER_ABSORB = 8571   // boss absorbs leaked sleepwalker
SLEEPWALKER_SPAWN  = 8572

// Parasite
NIGHTMARE_PARASITE_IDLE              = 8553
NIGHTMARE_PARASITE_ATTACK_RANGED     = 8554
NIGHTMARE_PARASITE_ATTACK_MAGIC      = 8555
NIGHTMARE_PARASITE_IMPREGNATE_PLAYER = 8556
NIGHTMARE_PARASITE_VOMIT             = 8557
NIGHTMARE_PARASITE_VOMIT_SPOTANIM    = 8558
NIGHTMARE_PARASITE_DEATH             = 8559
NIGHTMARE_PARASITE_SPAWN             = 8560
NIGHTMARE_PARASITE_STRONG            = 8561
NIGHTMARE_PARASITE_ATTACK_RANGED_MERGE = 9807

// Sanctuary state machine (the room around the pool)
NIGHTMARE_SANCTUARY_INACTIVE_TO_PREPARING = 8573
NIGHTMARE_SANCTUARY_PREPARING_IDLE        = 8574
NIGHTMARE_SANCTUARY_PREPARING_TO_ACTIVE   = 8575
NIGHTMARE_SANCTUARY_ACTIVE_TO_PREPARING   = 8576
NIGHTMARE_SANCTUARY_ACTIVE_IDLE_PHASE_01  = 8577
NIGHTMARE_SANCTUARY_ACTIVE_IDLE_PHASE_02  = 8578
NIGHTMARE_SANCTUARY_ACTIVE_IDLE_PHASE_03  = 8579
NIGHTMARE_SANCTUARY_PREPARING_TO_INACTIVE = 8580
NIGHTMARE_SANCTUARY_TO_INACTIVE           = 8581
NIGHTMARE_SANCTUARY_DEATH_LOOP            = 8582

// Player wake/sleep (entering the dream)
HUMAN_NIGHTMARE_WAKE  = 8583
HUMAN_NIGHTMARE_SLEEP = 8584

// Garden / pre-fight room
NIGHTMARE_GARDEN_WALKWAY_MID_FLOAT  = 8585
NIGHTMARE_GARDEN_WALKWAY_SIDE_FLOAT = 8586
NIGHTMARE_GARDEN_ISLAND_FLOATING    = 8590

// Player's Nightmare staff specials (incidental, in case the script auto-specs)
NIGHTMARE_STAFF_SPECIAL          = 8532
NIGHTMARE_STAFF_VOLATILE_HIT     = 8545
NIGHTMARE_STAFF_VOLATILE_CAST    = 8546
NIGHTMARE_STAFF_ELDRITCH_HIT     = 8547
NIGHTMARE_STAFF_ELDRITCH_PLAYER_CAST = 8548
HUMAN_NIGHTMARE_STAFF_READY      = 4504
HUMAN_NIGHTMARE_STAFF_CRUSH      = 4505
```

### `gameval.SpotanimID` (projectile / impact graphics)

```
NIGHTMARE_TELEPORT                  = 1763   // surge/teleport flash
NIGHTMARE_MAGIC_TRAVEL              = 1764   // boss magic projectile in flight
NIGHTMARE_MAGIC_IMPACT              = 1765   // boss magic projectile landing
NIGHTMARE_RANGED_TRAVEL             = 1766   // boss ranged projectile in flight
NIGHTMARE_RIFT                      = 1767   // grasping-claws shadow tile (highlight!)
NIGHTMARE_TOTEM_SPELL_TRAVEL        = 1768
NIGHTMARE_GARDEN_TOTEM_SPELL_IMPACT = 1769
NIGHTMARE_PARASITE_TRAVEL           = 1770   // parasite toss projectile
NIGHTMARE_PARASITE_MAGIC_TRAVEL     = 1771
NIGHTMARE_PARASITE_MAGIC_IMPACT     = 1772
NIGHTMARE_PARASITE_DRAIN_TRAVEL     = 1773
NIGHTMARE_PARASITE_HEAL_TRAVEL      = 1774
NIGHTMARE_PARASITE_RANGED_TRAVEL    = 1775
NIGHTMARE_HUSK_MAGIC_TRAVEL         = 1776
NIGHTMARE_HUSK_MAGIC_IMPACT         = 1777
NIGHTMARE_HUSK_RANGED_TRAVEL        = 1778
NIGHTMARE_PARASITE_VOMIT            = 1779
NIGHTMARE_PARASITE_VOMIT_WEAK       = 1780
NIGHTMARE_TRANCE_TRAVEL             = 1781   // curse projectile
NIGHTMARE_IMPACT_BLAST_SPOTANIM     = 1782
NIGHTMARE_FLIES                     = 1783   // flowers / quadrant flies
NIGHTMARE_INFECTION_SPOTANIM_DESPAWN = 2542
```

### `gameval.ObjectID` — Phosani-only objects

```
NIGHTMARE_CHALLENGE_PORTAL_ENABLED   = 29706
NIGHTMARE_CHALLENGE_PORTAL_DISABLED  = 29707
NIGHTMARE_CHALLENGE_SCOREBOARD       = 29708
NIGHTMARE_CHALLENGE_SLEEPWALKER_IDLE = 29709
NIGHTMARE_CHALLENGE_PORTAL           = 29710
```

The mushroom GameObject ID **37739** appears in the OpenOSRS plugin but isn't named in
`gameval/ObjectID.java` in this repo as `NIGHTMARE_*`; treat it as a numeric literal
when checking `GameObjectSpawned` events.

### Region

- **15256** — primary boss arena (used by both group and Phosani; a Phosani-only
  variant arena exists too, also reached at 15256 since the encounter is in an
  instanced version of the same chunk).
- The Piggy plugin used **15258** instead — that is a different sub-chunk. Check both
  if you want to be robust to instance variation.

To detect "in fight":

```kotlin
val region = client.localPlayer?.worldLocation?.regionID ?: 0
val inArena = region == 15256 || region == 15258
val inFight = inArena && bossNpc != null && bossNpc.id != NIGHTMARE_INITIAL
```

## Translated plugin logic

Distilled from the OpenOSRS plugin (`net.runelite.client.plugins.nightmare`) at
`commit 29b2a6b` of `open-osrs/runelite`. Reproduced here as Kotlin pseudocode. The
copyright on the original is (c) ASwatek under the BSD-2-Clause licence applied to
RuneLite plugins.

### State

```kotlin
private var nm: Rs2NpcModel? = null
private var inFight: Boolean = false

// Attack tracking
private var pendingAttack: NightmareAttack? = null
private var ticksUntilNextAttack: Int = 0

// Curse
private var cursed: Boolean = false
private var attacksSinceCurse: Int = 0

// Totems
private val totems: MutableMap<Int, MemorizedTotem> = HashMap()  // key = npc.index

// Inactive (dormant) totem ids — used to gate the "fight has started" flag
private val INACTIVE_TOTEM_IDS = setOf(9434, 9437, 9440, 9443)
```

### NightmareAttack enum (verbatim translation)

```kotlin
enum class NightmareAttack(
    val animation: Int,
    val prayer: Prayer,
    val tickColor: Color,
    val prayerSpriteId: Int,
) {
    MELEE       (8594, Prayer.PROTECT_FROM_MELEE,    Color.RED,   129),
    MAGIC       (8595, Prayer.PROTECT_FROM_MAGIC,    Color.CYAN,  127),
    RANGE       (8596, Prayer.PROTECT_FROM_MISSILES, Color.GREEN, 128),
    CURSE_MELEE (8594, Prayer.PROTECT_FROM_MISSILES, Color.GREEN, 128),
    CURSE_MAGIC (8595, Prayer.PROTECT_FROM_MELEE,    Color.RED,   129),
    CURSE_RANGE (8596, Prayer.PROTECT_FROM_MAGIC,    Color.CYAN,  127);

    companion object {
        fun forAnimation(animId: Int, cursed: Boolean): NightmareAttack? = when (animId) {
            8594 -> if (cursed) CURSE_MELEE else MELEE
            8595 -> if (cursed) CURSE_MAGIC else MAGIC
            8596 -> if (cursed) CURSE_RANGE else RANGE
            else -> null
        }
    }
}
```

### TotemPhase enum (verbatim from the OpenOSRS source)

```kotlin
enum class TotemPhase(val active: Boolean, val color: Color) {
    TOTEM_9434(true,  Color.ORANGE),  // dormant
    TOTEM_9437(true,  Color.ORANGE),
    TOTEM_9440(true,  Color.ORANGE),
    TOTEM_9443(true,  Color.ORANGE),
    TOTEM_9435(true,  Color.GREEN),   // ready/active
    TOTEM_9438(true,  Color.GREEN),
    TOTEM_9441(true,  Color.GREEN),
    TOTEM_9444(true,  Color.GREEN),
    TOTEM_9436(false, Color.RED),     // charged
    TOTEM_9439(false, Color.RED),
    TOTEM_9442(false, Color.RED),
    TOTEM_9445(false, Color.RED);

    companion object {
        fun fromId(id: Int): TotemPhase? = runCatching {
            valueOf("TOTEM_$id")
        }.getOrNull()
    }
}
```

### Event handlers (translated from `NightmarePlugin.java`)

```kotlin
@Subscribe
fun onAnimationChanged(e: AnimationChanged) {
    val actor = e.actor as? NPC ?: return
    val nm = this.nm ?: return
    if (actor !== nm) return

    when (actor.animation) {
        8594, 8595, 8596 -> {
            pendingAttack = NightmareAttack.forAnimation(actor.animation, cursed)
            ticksUntilNextAttack = 7
            if (cursed) {
                attacksSinceCurse++
                if (attacksSinceCurse >= 5) {
                    cursed = false
                    attacksSinceCurse = 0
                }
            }
        }
    }
}

@Subscribe
fun onChatMessage(e: ChatMessage) {
    if (e.type != ChatMessageType.GAMEMESSAGE) return
    when {
        e.message.contains("cursed you, shuffling your prayers") -> {
            cursed = true
            attacksSinceCurse = 0
        }
        e.message.contains("Nightmare's curse wear off") -> {
            cursed = false
            attacksSinceCurse = 0
        }
    }
}

@Subscribe
fun onNpcSpawned(e: NpcSpawned) {
    val npc = e.npc
    when (npc.id) {
        9432, 9423 -> { nm = npc; inFight = true }                 // initial wakeup NPC
        in 9434..9445 -> {                                         // any totem id
            val phase = TotemPhase.fromId(npc.id) ?: return
            totems[npc.index] = MemorizedTotem(npc, phase)
        }
    }
}

@Subscribe
fun onNpcDespawned(e: NpcDespawned) {
    if (e.npc === nm) reset()
    totems.remove(e.npc.index)
}

@Subscribe
fun onNpcDefinitionChanged(e: NpcChanged) {
    val npc = e.npc
    if (npc === nm && npc.id == 9433) reset()                      // dying
    if (npc.id in 9434..9445) {
        val phase = TotemPhase.fromId(npc.id) ?: return
        totems[npc.index] = MemorizedTotem(npc, phase)
    }
}

@Subscribe
fun onGameTick(e: GameTick) {
    if (ticksUntilNextAttack > 0) {
        ticksUntilNextAttack--
        if (ticksUntilNextAttack == 0) pendingAttack = null
    }
}

private fun reset() {
    nm = null
    inFight = false
    pendingAttack = null
    ticksUntilNextAttack = 0
    cursed = false
    attacksSinceCurse = 0
    totems.clear()
}
```

### Overlay logic (translated)

```kotlin
override fun render(g: Graphics2D): Dimension? {
    if (!client.isInInstancedRegion || !plugin.inFight) return null

    // 1. Highlight grasping-claws shadow tiles (orange).
    for (go in client.graphicsObjects) {
        if (go.id == 1767 /* NIGHTMARE_RIFT */) {
            val poly = Perspective.getCanvasTilePoly(client, go.location) ?: continue
            OverlayUtil.renderPolygon(g, poly, Color.ORANGE)
        }
    }

    // 2. Tick countdown above boss.
    val nm = plugin.nm
    val ticks = plugin.ticksUntilNextAttack
    if (config.tickCounter() && ticks > 0 && nm != null) {
        val text = ticks.toString()
        val canvas = Perspective.getCanvasTextLocation(client, g, nm.localLocation, text, 0)
        val color = if (ticks >= 4) plugin.pendingAttack?.tickColor ?: Color.WHITE else Color.WHITE
        renderTextWithShadow(g, text, 20, Font.BOLD, color, canvas)
    }

    // 3. Outline active totems with their phase colour.
    if (config.highlightTotems()) {
        for (m in plugin.totems.values) {
            if (m.phase.active) {
                val hull = m.npc.convexHull ?: continue
                g.color = m.phase.color
                g.draw(hull)
            }
        }
    }
    return null
}
```

### Prayer-suggestion overlay (BR corner)

```kotlin
override fun render(g: Graphics2D): Dimension? {
    if (!config.prayerHelper()) return null
    val attack = plugin.pendingAttack ?: return null
    if (!plugin.inFight || plugin.nm == null) return null

    val sprite = spriteManager.getSprite(attack.prayerSpriteId, 0) ?: return null
    val active = client.isPrayerActive(attack.prayer)
    panel.children.clear()
    panel.backgroundColor = if (active) STANDARD_BACKGROUND else NOT_ACTIVATED_BACKGROUND
    panel.children += ImageComponent(sprite)
    return panel.render(g)
}
```

`NOT_ACTIVATED_BACKGROUND = Color(150, 0, 0, 150)` — a translucent red panel that
turns green-ish as soon as the correct prayer is on. Visually shouts at the player.

### Auto-flick logic (Piggy NightmareHelper translation)

The Piggy plugin extended the overlay into a one-tick auto-prayer toggler. Pseudocode
matching the bug-fixed version of `NightmareHelperPlugin.handlePrayer`:

```kotlin
// Run on the client thread, from onGameTick after the boss anim has fired.
private fun handlePrayer() {
    val attack = pendingAttack ?: return
    val target: Prayer = when {
        cursed -> when (attack.animation) {
            8594 -> Prayer.PROTECT_FROM_MISSILES
            8595 -> Prayer.PROTECT_FROM_MELEE
            8596 -> Prayer.PROTECT_FROM_MAGIC
            else -> return
        }
        else -> attack.prayer
    }

    if (client.isPrayerActive(target)) return
    // Switch off any other protect prayers first to avoid leaving two on.
    setOf(Prayer.PROTECT_FROM_MELEE, Prayer.PROTECT_FROM_MAGIC, Prayer.PROTECT_FROM_MISSILES)
        .filter { it != target && client.isPrayerActive(it) }
        .forEach { Rs2Prayer.toggle(it, false) }
    Rs2Prayer.toggle(target, true)
}
```

The two known issues in the source Piggy code (worth fixing if you copy it):

1. `togglePrayer` was called twice in a row, which cancels the toggle. Don't.
2. The "force prayer tab open" path inside `onGameTick` thrashed the UI; only open
   the tab if the player isn't already on it and you actually need to click an icon.
3. `onNpcSpawned` had an empty conditional. Replace with the full
   `9432/9434..9445` handling above.

In this Microbot fork, the canonical low-latency dispatch for prayer toggles is
`client.menuAction` direct dispatch — see the project memory entry "Direct
`client.menuAction` dispatch for lowest-latency invocation" and the existing
`pvmprayflick/PvmPrayFlick.kt` for a tick-perfect example. The Nightmare's 7-tick
window is generous enough that even `Microbot.doInvoke` works, but if you ever flick
through a curse + protection-deactivation sequence the direct dispatch path is
safer.

## Implementation guide for Microbot script authors

If you want to build `trent/nightmare/Nightmare.kt`, the natural shape:

1. **Plugin class** with `@PluginDescriptor(name = PluginDescriptor.Trent + "Nightmare", ...)`,
   following the `Combat.kt` pattern in this folder's sibling.
2. **Subscribe to** `AnimationChanged`, `ChatMessage`, `NpcSpawned`, `NpcDespawned`,
   `NpcChanged`, `GameTick`, `GraphicsObjectCreated`, `ProjectileMoved`. Use
   `@Subscribe` directly on the plugin class — no need for a separate handler class
   for an overlay-style helper.
3. **Cache lookups** — use `Microbot.getRs2NpcCache().query()...` to find the boss
   and totems each tick. Don't store NPC refs across ticks; they go stale.
4. **Threading** — anything that touches `client.menuAction`, `Rs2Prayer.toggle`,
   or interacts with widgets must be on the client thread. Use
   `Microbot.getClientThread().invoke { ... }`.
5. **Auto-prayer** — see the Piggy translation above. Bind to a config flag and
   default OFF; flicking is high-risk for ban-flag, so make it opt-in.
6. **Overlays** — register the boss-tick overlay (`OverlayLayer.ABOVE_SCENE`) and
   prayer-suggestion overlay (`OverlayLayer.ABOVE_WIDGETS`, position
   `BOTTOM_RIGHT`). Both already shipped in OpenOSRS form above.
7. **Region gating** — only run any of this when the player's region id is 15256
   (or 15258 for the Piggy variant). Reset all state when leaving.
8. **Walk / movement** — for an "auto-walk to safe quadrant" extension, watch for
   anim 8601 (segment), then within ~2 ticks scan the arena's GameObjects for
   `NIGHTMARE_FLIES = 1783` spotanim; the quadrant whose tiles have that spotanim
   is safe. Rs2Walker can path-walk to any tile in that quadrant.
9. **Sleepwalker auto-attack** — query `NIGHTMARE_SLEEPWALKER_*` (group) or
   `NIGHTMARE_CHALLENGE_SLEEPWALKER` (Phosani) within range, attack the closest
   one with `Rs2Npc.attack`. Filter out the pre-fight ambient ids 1029–1032 / 9801
   / 9802 so you don't run off into Slepe.
10. **Husk auto-attack** — similar, target the ranged husk first (id 9455 / 9467).
    Crush weapons get max-roll on husks.
11. **Parasite handling** — when the player's own animation is 8556
    (`PARASITE_IMPREGNATE_PLAYER`), start an 18-tick timer. At ~10–12 ticks in,
    drink Sanfew serum from inventory. After the parasite NPC spawns
    (id 9452/9453/9468/9469) target it directly.
12. **Logging** — on first time you observe an unrecognised animation while in the
    arena, log it. The cache rev evolves; new anims will sneak in for desperation
    and these tables need updating.

## Gear / inventory presets (wiki — for an in-plugin recommender)

### Group melee (max)

- Inquisitor's helm / hauberk / plateskirt
- Amulet of rancour
- Infernal cape
- Scythe of vitur (crush) + Avernic defender
- Ferocious gloves, Avernic treads
- Ultor ring
- Specs: Voidwaker > Saradomin godsword > Granite maul (ornate) > Dragon claws

### Group magic (totem-spec / sleepwalker clears)

- Ancestral hat / robe top / robe bottom
- Occult necklace
- Tumeken's shadow > Eye of Ayak > Harmonised Nightmare staff
- Confliction gauntlets
- Avernic treads
- Magus ring

### Phosani's solo recommended supplies

- 6–11 Anglerfish
- 3 × 4-dose Prayer potions
- 1 × Divine super combat potion
- 1 × Sanfew serum (4-dose)
- Stamina potion if no Drakan's medallion
- Book of the dead (mage)
- Divine rune pouch (mage)

## What this guide deliberately does not include

- **No automation script**. The user explicitly asked only for the translated
  resource. To author the actual plugin, follow `trent/api/StateMachineScript`
  patterns and the `pvmprayflick/PvmPrayFlick.kt` example.
- **No detection-evasion advice**. The Nightmare-specific automation patterns
  above are useful for a personal helper / prayer-suggestion overlay; auto-flicking
  prayers in a real fight is a high-risk ban category and should not be done
  without explicit user direction.
