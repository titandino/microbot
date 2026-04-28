package net.runelite.client.plugins.microbot.trent.pvmprayflick

import com.google.inject.Provides
import net.runelite.api.Client
import net.runelite.api.MenuAction
import net.runelite.api.NPC
import net.runelite.api.Projectile
import net.runelite.api.events.AnimationChanged
import net.runelite.api.events.NpcDespawned
import net.runelite.api.events.ProjectileMoved
import net.runelite.api.gameval.AnimationID
import net.runelite.api.gameval.NpcID
import net.runelite.api.gameval.SpotanimID
import net.runelite.client.config.Config
import net.runelite.client.config.ConfigGroup
import net.runelite.client.config.ConfigItem
import net.runelite.client.config.ConfigManager
import net.runelite.client.eventbus.Subscribe
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.Script
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcQueryable
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum
import java.awt.Rectangle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * PVM Prayer Flicker — animation-event-driven, damage-tick-correct.
 *
 * ## Tick math (authoritative, from user)
 *
 * For Jad-family bosses, the server calculates damage some number of ticks
 * after the attack animation starts. The toggle must be ACTIVE at the damage
 * tick; the server applies our menu-action toggle at the NEXT tick after we
 * invoke. Empirically (user-tuned against live Jad reps), the correct fire
 * point is `T + attackDelayTicks` — one tick later than a naive "invoke the
 * tick before damage" model would suggest. This bump was necessary because
 * consecutive 1-tick-offset Jads hitting with different styles were missing
 * flicks at the old `T + attackDelayTicks - 1` offset.
 *
 * Formally, if the animation is observed at tick T, we invoke the toggle at
 * `T + attackDelayTicks`. That's the value stored in [PendingToggle.fireAtTick].
 *
 * Consecutive 1-tick-offset Jads (from the user's example, with `attackDelayTicks = 3`):
 *
 * - Jad A anim at T   (magic) → toggle MAGIC at T+3
 * - Jad B anim at T+1 (range) → toggle RANGE at T+4
 * - Jad C anim at T+2 (magic) → toggle MAGIC at T+5
 *
 * Each tick has exactly one fire. Applied next tick → correct prayer when
 * damage resolves.
 *
 * ## GWD limitation
 *
 * For GWD (Graardor / K'ril / Zilyana / Kree'arra) damage lands on the
 * animation tick itself (`attackDelayTicks = 0`), so the invoke would need
 * to fire on `T` at the very latest. We can't see the future reactively, so
 * the first hit of each attack lands with whatever prayer was previously
 * active. We still register the toggle (`fireAtTick = T`), and because the
 * poll loop fires any pending toggle with `fireAtTick <= nowTick` immediately,
 * it goes out on the very next poll cycle (≤10 ms). This is "well-enough"
 * for GWD and avoids reintroducing predictive / cadence-based scheduling
 * (explicitly ripped out previously).
 *
 * ## Architecture
 *
 * - `@Subscribe onAnimationChanged` is the one and only trigger. It's on the
 *   client thread — does nothing but a type check, a tickCount read, a boss
 *   match, and a `ConcurrentHashMap` put. No game-state reads, no menu
 *   dispatch. Records a [PendingToggle] keyed by NPC index.
 *
 * - Background poll (10 ms) drains [pendingToggles], firing any whose
 *   `fireAtTick <= nowTick`. Each fire is a single cursorless
 *   `Microbot.doInvoke(entry, Rectangle(1, 1))` call.
 *
 * - Per-NPC [attackCooldowns] still guards against phantom re-schedules from
 *   lingering animations inside one attack cycle.
 *
 * ## Cursorless invocation
 *
 * [invokePrayerMenuEntry] calls `Microbot.doInvoke(entry, Rectangle(1, 1))`:
 *   - `Rs2UiHelper.getClickingPoint` short-circuits on the `(0, 0)`-origin
 *     1x1 rect and returns `Point(1, 1)`.
 *   - `VirtualMouse.click` gates natural-mouse on `point.x > 1 && y > 1` —
 *     false — so `naturalMouse.moveTo(...)` is skipped. OS cursor never moves.
 *   - `MicrobotPlugin.onMenuEntryAdded` (priority 999) intercepts the
 *     synthetic MOUSE_CLICKED event, nukes the real menu entries, and
 *     replaces them with the forced entry from `Microbot.targetMenu`.
 *
 * `client.menuAction` direct-dispatch was tried (2026-04-24) and proven to
 * silently fail when the prayer widget isn't in a clickable state. Do NOT
 * swap to it.
 */
@ConfigGroup("pvmPrayFlick")
interface PvmPrayFlickConfig : Config {
    /**
     * When ON, releases the protection prayer on polls where no pending
     * toggle is queued and no currently-matched boss is animating an attack.
     * When OFF (default), prayer stays on between attacks — burns more
     * prayer but is strictly safer.
     */
    @ConfigItem(
        keyName = "conservePrayerPoints",
        name = "Conserve prayer points (experimental)",
        description = "When ON, turns off the protection prayer when no boss is mid-attack animation. " +
            "When OFF (default), prayer stays on between attacks — safer but drains prayer faster.",
        position = 0,
    )
    fun conservePrayerPoints(): Boolean = false

    /**
     * When ON, every animation-change event involving an NPC whose id is in
     * any [BossConfig.npcIds] (or whose name substring-matches any keyword)
     * is logged via `Microbot.log`, plus matcher/dispatch trace events. Used
     * to verify the listener is actually receiving Hunllef events and the
     * dispatch path reaches the prayer orb. Default ON until validated.
     */
    @ConfigItem(
        keyName = "debugLogging",
        name = "Debug logging (verbose)",
        description = "Logs every boss animation change, matcher result, and dispatched toggle. " +
            "Default ON; turn off once Hunllef firing is confirmed in-arena.",
        position = 1,
    )
    fun debugLogging(): Boolean = true
}

@PluginDescriptor(
    name = PluginDescriptor.Trent + "PVM Prayer Flicker",
    description = "Reactive prayer flicker for GWD + Jad variants + Hunllef (damage-tick-correct scheduling)",
    tags = [
        "combat", "prayer", "flicker", "gwd", "godwars", "inferno", "jad", "flick",
        "ket-rak", "gauntlet", "hunllef", "corrupted",
    ],
    enabledByDefault = false,
)
class PvmPrayFlick : Plugin() {

    @Inject
    private lateinit var config: PvmPrayFlickConfig

    /**
     * Guice-injected RuneLite client. Kotlin can't see Lombok-generated
     * `Microbot.getClient()` reliably; `@Inject` gives us the same reference
     * without the cross-compiler visibility gap.
     */
    @Inject
    private lateinit var client: Client

    @Provides
    fun provideConfig(configManager: ConfigManager): PvmPrayFlickConfig =
        configManager.getConfig(PvmPrayFlickConfig::class.java)

    private val script = PvmPrayFlickScript()

    override fun startUp() {
        script.conservePoints = config.conservePrayerPoints()
        script.debugLogging = config.debugLogging()
        script.client = client
        script.startScript()
        Microbot.log(
            "[PvmPrayFlick] plugin started (conservePrayerPoints=${script.conservePoints}, " +
                "debugLogging=${script.debugLogging})"
        )
        // One-time-per-session watchlist dump so the operator can grep for it
        // and confirm 9021-9024 + 9035-9038 etc are actually in scope.
        if (script.debugLogging) {
            for (cfg in BOSSES) {
                Microbot.log(
                    "[PvmPrayFlick] watching name~='${cfg.nameKeyword}' npcIds=${cfg.npcIds.sorted()} " +
                        "anims=${cfg.attacks.keys.sorted()} delay=${cfg.attackDelayTicks}t cooldown=${cfg.cooldownTicks}t"
                )
            }
        }
    }

    override fun shutDown() {
        script.shutdown()
    }

    /**
     * The only trigger in the whole plugin. Fires on the client thread at the
     * exact tick an actor's animation changes. We do ONLY non-blocking work
     * here — a type check, a tickCount read, a boss match, and a concurrent
     * map put on the script. All menu-entry dispatch stays on the poll thread.
     *
     * Matching here (instead of deferring to the poll loop) keeps the
     * pending-toggle map tight — we only record entries for NPCs we actually
     * care about.
     */
    @Subscribe
    fun onAnimationChanged(event: AnimationChanged) {
        val actor = event.actor
        if (actor !is NPC) return
        val anim = actor.animation
        val id = actor.id
        val name = actor.name
        val debug = script.debugLogging

        // Diagnostic: log every event involving a boss-watchlist NPC, even if
        // matching fails downstream. ALL_BOSS_IDS is the union of every
        // BossConfig.npcIds entry — cheap O(1) Int set lookup.
        val onWatchlist = id in ALL_BOSS_IDS
        if (debug && onWatchlist) {
            Microbot.log(
                "[PvmPrayFlick] anim-event: name='$name' id=$id anim=$anim tick=${client.tickCount}"
            )
        }

        val config = matchBoss(actor)
        if (config == null) {
            // If a Hunllef-anim id (8754/8755) fires but the matcher returned
            // null, the operator needs to see it — that's the smoking-gun for
            // a constants mismatch.
            if (debug && (anim == 8754 || anim == 8755)) {
                Microbot.log(
                    "[PvmPrayFlick] HUNLLEF-ANIM-NOMATCH: anim=$anim id=$id name='$name' " +
                        "(boss matcher rejected — npcId not in any BossConfig.npcIds and name didn't substring any keyword)"
                )
            }
            return
        }

        val prayer = config.attacks[anim]
        if (prayer == null) {
            // We matched the boss but this animation isn't a *transition* attack
            // animation. For Hunllef this is expected: in-stance attacks fire
            // animation 8419 / -1 and are detected via ProjectileMoved instead
            // of AnimationChanged. The renamed log makes that explicit so the
            // operator doesn't think we're discarding damaging anims.
            if (debug && config.nameKeyword == "hunllef") {
                Microbot.log(
                    "[PvmPrayFlick] hunllef-anim-only-info: anim=$anim id=$id (matched boss but " +
                        "anim is not a stance-transition; in-stance attacks handled via projectile path)"
                )
            }
            return
        }

        val nowTick = client.tickCount

        if (debug) {
            Microbot.log(
                "[PvmPrayFlick] match boss='${config.nameKeyword}' id=$id anim=$anim → $prayer @ tick=$nowTick"
            )
        }

        // Cooldown guard: if we already scheduled a toggle for this NPC this
        // attack cycle, ignore re-fires from lingering animation events.
        if (script.isWithinCooldown(actor.index, nowTick, config.cooldownTicks)) {
            if (debug) {
                Microbot.log(
                    "[PvmPrayFlick] cooldown-skip boss='${config.nameKeyword}' npcIndex=${actor.index} " +
                        "tick=$nowTick within ${config.cooldownTicks}t of last schedule"
                )
            }
            return
        }

        // fireAtTick = T + attackDelayTicks.
        // Toggle invoked at this tick → server applies next tick → prayer
        // active when damage is calculated at T + attackDelayTicks + 1.
        val fireAtTick = nowTick + config.attackDelayTicks
        // Skip scheduling entirely if the prayer is ALREADY up at scheduling
        // time. The maintenance loop is the safety net for prayer-drain
        // knockoffs; for the reactive path there's nothing to dispatch when
        // we'd be toggling a prayer that's already active. Reading from the
        // varbit cache here is free — we're on the client thread.
        if (Rs2Prayer.isPrayerActive(prayer)) {
            // Stamp the cooldown anyway so phantom re-fires from lingering
            // animations get dropped.
            script.stampAttackCooldown(actor.index, nowTick)
            if (debug) {
                Microbot.log(
                    "[PvmPrayFlick] schedule-skip prayer=$prayer already-active for " +
                        "boss='${config.nameKeyword}' anim=$anim tick=$nowTick"
                )
            }
        } else {
            script.schedulePendingToggle(
                npcIndex = actor.index,
                npcName = name ?: "<unnamed>",
                prayer = prayer,
                fireAtTick = fireAtTick,
                animationTick = nowTick,
            )
        }

        // Stance latch for maintain-flagged bosses (Hunllef). Once latched
        // here, the poll-loop maintenance path keeps `prayer` active across
        // prayer-drain knockoffs until the next stance-transition anim flips
        // it to the other style. The reactive `schedulePendingToggle` above
        // still owns the FIRST activation (with attackDelayTicks alignment);
        // maintenance only re-activates if the prayer goes down later.
        if (config.maintainPrayer) {
            script.setExpectedPrayer(prayer, anim)
        }

        if (debug) {
            Microbot.log(
                "[PvmPrayFlick] scheduled boss='${config.nameKeyword}' anim=$anim tick=$nowTick " +
                    "fireAtTick=$fireAtTick prayer=$prayer"
            )
        }
    }

    /**
     * Hunllef despawn → clear the maintenance latch. Without this the script
     * would keep slamming PROTECT_MAGIC after the kill while the player walks
     * to the next room / chest, until they happen to leave the region.
     *
     * We only act on NPCs whose id is in some [BossConfig] with
     * `maintainPrayer = true`, so non-Hunllef despawns are a no-op.
     */
    @Subscribe
    fun onNpcDespawned(event: NpcDespawned) {
        val npc = event.npc ?: return
        val cfg = matchBoss(npc) ?: return
        if (!cfg.maintainPrayer) return
        script.clearExpectedPrayer("npc-despawned id=${npc.id} name='${npc.name}'")
    }

    /**
     * Hunllef in-stance attacks (the bulk of the fight) fire animation 8419
     * or -1 — neither matches our 8754/8755 transition anims. Damage on those
     * attacks is delivered via projectiles (ids 1701/1707/1708 magic,
     * 1705/1711/1712 range; 1713/1714 are prayer-drain and explicitly NOT
     * flicked). We subscribe here in addition to the animation path because
     * the hub plugin's bytecode confirmed both signals are needed for full
     * coverage. The hub plugin uses a naive `Rs2Prayer.toggle(prayer, true)`
     * directly inside `onProjectileMoved` (no tick math, no dedup) — it works
     * because (a) `toggle(_, true)` is idempotent and (b) the projectile is
     * in flight for ~3 ticks so the toggle "stays on" naturally. We instead
     * route through the same scheduling pipeline as the animation path so the
     * Rectangle(1,1) cursorless dispatch contract and per-tick fire ordering
     * are preserved.
     *
     * Tick-delay formula (per user spec):
     *   damageTick  = client.tickCount + ceil(remainingCycles / 30.0)
     *   fireAtTick  = damageTick - 1
     * Server applies our toggle one tick after invoke, so `fireAtTick` lines
     * up the prayer activation with the damage tick. (One client cycle = 20ms;
     * one game tick = 30 cycles = 600ms.)
     *
     * Per-projectile dedup keys on `System.identityHashCode(projectile)`:
     * `ProjectileMoved` fires every tick the projectile is in flight, but the
     * underlying object is the same. We schedule once per projectile instance
     * and let the schedule cooldown stamp guard against re-fires from the
     * animation path on the same NPC.
     */
    @Subscribe
    fun onProjectileMoved(event: ProjectileMoved) {
        val projectile = event.projectile ?: return
        val projId = projectile.id
        val debug = script.debugLogging

        // Find which boss (if any) claims this projectile id. Iterating is
        // fine — at most ~6 entries. The first matching boss wins.
        var matched: BossConfig? = null
        var prayer: Rs2PrayerEnum? = null
        for (cfg in BOSSES) {
            val p = cfg.projectiles[projId]
            if (p != null) {
                matched = cfg
                prayer = p
                break
            }
        }
        if (matched == null || prayer == null) return

        val nowTick = client.tickCount
        val projKey = System.identityHashCode(projectile)
        val firstSighting = script.markProjectileSeen(projKey, nowTick)

        // Throttle the verbose `proj-event` log: only print on the first tick
        // we see a given projectile object. Subsequent ticks during its flight
        // are silent.
        if (debug && firstSighting) {
            val targetName = projectile.targetActor?.name ?: "<none>"
            Microbot.log(
                "[PvmPrayFlick] proj-event: id=$projId cycles=${projectile.remainingCycles} " +
                    "interacting='$targetName' tick=$nowTick"
            )
        }

        if (!firstSighting) return

        // ceil(remainingCycles / 30.0) — game tick = 30 client cycles.
        val ticksUntilDamage = (projectile.remainingCycles + 29) / 30
        val fireAtTick = nowTick + ticksUntilDamage - 1

        if (debug) {
            Microbot.log(
                "[PvmPrayFlick] match boss='${matched.nameKeyword}' projectile id=$projId → $prayer " +
                    "@ tick=$nowTick fireAtTick=$fireAtTick (ticksUntilDamage=$ticksUntilDamage)"
            )
        }

        // Use a synthetic NPC index keyed off projectile identity so the
        // pendingToggles map can hold simultaneous projectile + animation
        // schedules without one overwriting the other. Negative range
        // distinguishes from real NPC indices (which are positive).
        val syntheticKey = -(projKey and 0x7FFFFFFF) - 1
        // Same already-active gate as the animation path — the bulk of
        // Hunllef polls were no-op dispatches against an active prayer that
        // backed up the menu queue. Skip scheduling outright; the
        // maintenance loop catches drain knockoffs.
        if (Rs2Prayer.isPrayerActive(prayer)) {
            if (debug) {
                Microbot.log(
                    "[PvmPrayFlick] schedule-skip prayer=$prayer already-active for " +
                        "boss='${matched.nameKeyword}' projectile=$projId tick=$nowTick"
                )
            }
            return
        }
        script.schedulePendingToggle(
            npcIndex = syntheticKey,
            npcName = "hunllef-projectile-$projId",
            prayer = prayer,
            fireAtTick = fireAtTick,
            animationTick = nowTick,
        )

        if (debug) {
            Microbot.log(
                "[PvmPrayFlick] scheduled boss='${matched.nameKeyword}' projectile=$projId tick=$nowTick " +
                    "fireAtTick=$fireAtTick prayer=$prayer"
            )
        }
    }

    /**
     * Matches an NPC to a [BossConfig]. Id match first (O(1) over a small
     * set, definitive); name substring is the fallback for variants whose
     * ids aren't enumerated — primarily TzHaar-Ket-Rak Jads. Duplicated from
     * the script class because this @Subscribe runs on the client thread and
     * we want to filter before any map writes happen.
     */
    private fun matchBoss(npc: NPC): BossConfig? {
        val id = npc.id
        val name = npc.name
        for (cfg in BOSSES) {
            if (id in cfg.npcIds) return cfg
            if (name != null && name.contains(cfg.nameKeyword, ignoreCase = true)) return cfg
        }
        return null
    }
}

/**
 * Per-boss configuration. Matching rule: NPC id OR name contains
 * [nameKeyword] (case-insensitive). Name fallback catches Jad variants
 * whose ids aren't enumerated (TzHaar-Ket-Rak challenge spawns).
 *
 * [attackDelayTicks] is the gap between the attack animation starting and
 * the server calculating damage for that attack, measured in game ticks.
 *   - Jad-family: **3 ticks** (user authoritative — anim+3)
 *   - GWD bosses: **0 ticks** (damage on the animation tick itself; see class
 *     KDoc for the "first hit lands with previous prayer" limitation)
 *
 * [cooldownTicks] is the per-NPC "don't re-schedule a toggle from a lingering
 * animation in the same attack cycle" rearm window. Sized to cover animation
 * playback while still clearing before the genuine next attack.
 *
 * [attacks] maps observed attack animation id to the protection prayer that
 * attack demands.
 *
 * [projectiles] maps projectile/spotanim id (as reported by `ProjectileMoved`)
 * to the protection prayer that projectile demands. Used by the projectile
 * subscriber for bosses (Hunllef) whose in-stance attacks don't fire a
 * distinct attack animation. Empty by default — most bosses (GWD, Jad-family)
 * rely solely on the animation path.
 *
 * [maintainPrayer] enables the **stance maintenance loop**: while a matched
 * NPC of this config is alive in the scene, the script tracks the most
 * recently signalled "expected" prayer (set on the [attacks] transition
 * animation) and re-activates it on every poll cycle if it isn't currently
 * up. Required for Hunllef because the boss's prayer-drain attack (projectiles
 * 1713/1714) toggles off the active overhead between damaging attacks; the
 * reactive paths only fire on a NEW attack signal, so without maintenance the
 * player would eat the next mage/range hit unprotected. Other bosses leave
 * this `false` — their style only changes when an attack animation plays, and
 * the prayer is never knocked off mid-stance.
 */
private data class BossConfig(
    val npcIds: Set<Int>,
    val nameKeyword: String,
    val attackDelayTicks: Int,
    val cooldownTicks: Int,
    val attacks: Map<Int, Rs2PrayerEnum>,
    val projectiles: Map<Int, Rs2PrayerEnum> = emptyMap(),
    val maintainPrayer: Boolean = false,
)

/**
 * Bosses handled by this flicker. Ids first, then name fallback. All Jad
 * variants (Fight Caves + Inferno + TzHaar-Ket-Rak) share one entry keyed on
 * the "jad" substring; Ket-Rak challenge Jads with non-enumerated ids are
 * caught by the name fallback.
 */
/**
 * Pre-flattened union of every [BossConfig.npcIds] set. Used by the listener
 * for the watchlist-debug log line so we can confirm a Hunllef event hit the
 * subscriber even when subsequent matching/dispatch fails.
 */
private val ALL_BOSS_IDS: Set<Int> by lazy { BOSSES.flatMap { it.npcIds }.toSet() }

private val BOSSES: List<BossConfig> = listOf(
    // General Graardor. Damage lands on the animation tick (attackDelayTicks=0)
    // → reactive toggle lands one tick late on the first hit. See class KDoc.
    BossConfig(
        npcIds = setOf(
            NpcID.GODWARS_BANDOS_AVATAR,
            NpcID.CLANCUP_GODWARS_BANDOS_AVATAR,
        ),
        nameKeyword = "graardor",
        attackDelayTicks = 0,
        cooldownTicks = 5,
        attacks = mapOf(
            AnimationID.GODWARS_BANDOS_ATTACK to Rs2PrayerEnum.PROTECT_MELEE,
            AnimationID.GODWARS_BANDOS_RANGED to Rs2PrayerEnum.PROTECT_RANGE,
        ),
    ),

    // K'ril Tsutsaroth. Same GWD damage-on-animation-tick semantics.
    BossConfig(
        npcIds = setOf(
            NpcID.GODWARS_ZAMORAK_AVATAR,
            NpcID.CLANCUP_GODWARS_ZAMORAK_AVATAR,
        ),
        nameKeyword = "k'ril",
        attackDelayTicks = 0,
        cooldownTicks = 4,
        attacks = mapOf(
            AnimationID.GODWARS_ZAMORAK_ATTACK to Rs2PrayerEnum.PROTECT_MELEE,
            AnimationID.GODWARS_ZAMORAK_MAGIC_ATTACK to Rs2PrayerEnum.PROTECT_MAGIC,
        ),
    ),

    // Commander Zilyana. Same GWD damage-on-animation-tick semantics.
    BossConfig(
        npcIds = setOf(
            NpcID.GODWARS_SARADOMIN_AVATAR,
            NpcID.CLANCUP_GODWARS_SARADOMIN_AVATAR,
        ),
        nameKeyword = "zilyana",
        attackDelayTicks = 0,
        cooldownTicks = 4,
        attacks = mapOf(
            AnimationID.GODWARS_SARADOMIN_ATTACK to Rs2PrayerEnum.PROTECT_MELEE,
            AnimationID.GODWARS_SARADOMIN_MAGIC_ATTACK to Rs2PrayerEnum.PROTECT_MAGIC,
        ),
    ),

    // Kree'arra. Same GWD damage-on-animation-tick semantics.
    BossConfig(
        npcIds = setOf(
            NpcID.GODWARS_ARMADYL_AVATAR,
            NpcID.CLANCUP_GODWARS_ARMADYL_AVATAR,
        ),
        nameKeyword = "kree'arra",
        attackDelayTicks = 0,
        cooldownTicks = 3,
        attacks = mapOf(
            AnimationID.GODWARS_ARMADYL_CANNON_ATTACK to Rs2PrayerEnum.PROTECT_RANGE,
            AnimationID.GODWARS_ARMADYL_SPEAR_ATTACK to Rs2PrayerEnum.PROTECT_MAGIC,
            AnimationID.GODWARS_ARMADYL_SWORD_ATTACK to Rs2PrayerEnum.PROTECT_MELEE,
        ),
    ),

    // All Jad variants: Fight Caves TzTok-Jad, Inferno JalTok-Jad, and
    // TzHaar-Ket-Rak challenge spawns. `attackDelayTicks = 3` per user
    // ground truth — damage lands 3 ticks after animation (anim+3).
    // `cooldownTicks = 7` spans the animation playback window (~6-7 ticks)
    // plus a 1-tick buffer; genuine next attack at +8 ticks is unaffected,
    // phantom re-reads at +5/+6 are dropped.
    BossConfig(
        npcIds = setOf(
            NpcID.INFERNO_JAD,
            NpcID.INFERNO_JAD_FINALWAVE,
            // Legacy TzTok-Jad id — not in gameval NpcID; canonical value
            // from runelite-api/NpcID.java TZTOKJAD = 3127.
            3127,
        ),
        nameKeyword = "jad",
        attackDelayTicks = 3,
        cooldownTicks = 7,
        attacks = mapOf(
            // JalTok-Jad (Inferno + Ket-Rak).
            AnimationID.JALTOKJAD_ATTACK_MAGIC to Rs2PrayerEnum.PROTECT_MAGIC,
            AnimationID.JALTOKJAD_ATTACK_RANGED to Rs2PrayerEnum.PROTECT_RANGE,
            AnimationID.JALTOKJAD_ATTACK_MELEE to Rs2PrayerEnum.PROTECT_MELEE,
            // TzTok-Jad (Fight Caves) — no gameval constants; documented at
            // runelite-api/AnimationID.java and the OSRS Wiki.
            2656 to Rs2PrayerEnum.PROTECT_MAGIC,   // magic attack
            2652 to Rs2PrayerEnum.PROTECT_RANGE,   // ranged attack
            2653 to Rs2PrayerEnum.PROTECT_MELEE,   // melee bite
        ),
    ),

    // Crystalline + Corrupted Hunllef (Gauntlet). Folded in from the
    // standalone gauntletprayer plugin (now deleted) so toggling between
    // bosses is one config knob. Hunllef plays a "transition" animation
    // (8754 magic, 8755 ranged) ONLY on stance changes; in-stance attacks
    // fire animation 8419/-1 (silent). All damaging attacks emit a projectile
    // — that's the authoritative signal for the bulk of the fight. Animation
    // path remains for the pre-emptive transition flick.
    //
    // Projectile ids (verified against AutoGauntletPrayerPlugin bytecode):
    //   1701 CRYSTAL_DRAGON_MAGIC_TRAVEL    (miniboss / first-room dragon — magic)
    //   1707 CRYSTAL_HUNLLEF_MAGIC_TRAVEL          (normal Hunllef)
    //   1708 CRYSTAL_HUNLLEF_MAGIC_TRAVEL_HM       (Corrupted Hunllef)
    //   1705 CRYSTAL_DARK_BEAST_RANGE_TRAVEL (miniboss — range)
    //   1711 CRYSTAL_HUNLLEF_RANGE_TRAVEL          (normal Hunllef)
    //   1712 CRYSTAL_HUNLLEF_RANGE_TRAVEL_HM       (Corrupted Hunllef)
    //   1713/1714 CRYSTAL_HUNLLEF_PRAYER_TRAVEL{,_HM} — DELIBERATELY ABSENT.
    //     These are prayer-drain projectiles; they don't deal style damage
    //     and flicking on them would waste prayer / mis-prioritize the next
    //     real attack. Hub plugin also omits them.
    BossConfig(
        npcIds = setOf(
            NpcID.CRYSTAL_HUNLLEF_MELEE,
            NpcID.CRYSTAL_HUNLLEF_RANGED,
            NpcID.CRYSTAL_HUNLLEF_MAGIC,
            NpcID.CRYSTAL_HUNLLEF_DEATH,
            NpcID.CRYSTAL_HUNLLEF_MELEE_HM,
            NpcID.CRYSTAL_HUNLLEF_RANGED_HM,
            NpcID.CRYSTAL_HUNLLEF_MAGIC_HM,
            NpcID.CRYSTAL_HUNLLEF_DEATH_HM,
        ),
        nameKeyword = "hunllef",
        attackDelayTicks = 3,
        cooldownTicks = 4,
        attacks = mapOf(
            AnimationID.HUNLLEF_ATTACK_TRANSITION_MAGIC to Rs2PrayerEnum.PROTECT_MAGIC,
            AnimationID.HUNLLEF_ATTACK_TRANSITION_RANGED to Rs2PrayerEnum.PROTECT_RANGE,
        ),
        projectiles = mapOf(
            SpotanimID.CRYSTAL_DRAGON_MAGIC_TRAVEL to Rs2PrayerEnum.PROTECT_MAGIC,
            SpotanimID.CRYSTAL_HUNLLEF_MAGIC_TRAVEL to Rs2PrayerEnum.PROTECT_MAGIC,
            SpotanimID.CRYSTAL_HUNLLEF_MAGIC_TRAVEL_HM to Rs2PrayerEnum.PROTECT_MAGIC,
            SpotanimID.CRYSTAL_DARK_BEAST_RANGE_TRAVEL to Rs2PrayerEnum.PROTECT_RANGE,
            SpotanimID.CRYSTAL_HUNLLEF_RANGE_TRAVEL to Rs2PrayerEnum.PROTECT_RANGE,
            SpotanimID.CRYSTAL_HUNLLEF_RANGE_TRAVEL_HM to Rs2PrayerEnum.PROTECT_RANGE,
            // Prayer-drain projectiles 1713/1714 deliberately absent.
        ),
        // Hunllef's prayer-drain knocks the overhead off mid-stance. The
        // maintenance loop re-activates [PvmPrayFlickScript.expectedPrayer]
        // on every 10 ms poll if `Rs2Prayer.isPrayerActive` reports it down.
        // Set on attack-anim 8754/8755, cleared on NPC despawn or "no Hunllef
        // in scene" detection. Other bosses keep this `false`.
        maintainPrayer = true,
    ),
)

/**
 * A prayer toggle queued by the `@Subscribe` handler. Fired by the poll loop
 * on the first tick where `fireAtTick <= nowTick`.
 *
 * [animationTick] is retained for logging only — lets us see
 * "anim at T, firing at T+1" in the Microbot.log stream.
 */
private data class PendingToggle(
    val npcIndex: Int,
    val npcName: String,
    val prayer: Rs2PrayerEnum,
    val fireAtTick: Int,
    val animationTick: Int,
)

/**
 * Per-dispatch cooldown (ms) for the maintenance loop's prayer re-activation.
 *
 * Sized to span one full server tick (600 ms) plus a margin for varbit-flip
 * latency, but capped well below 600 ms so a missed dispatch (e.g., the
 * client thread was stalled when the doInvoke landed) recovers within
 * ~half a tick rather than holding the player undefended for a full second.
 *
 * 250 ms is the chosen balance: at 10 ms poll cadence we get at most one
 * dispatch per ~25 polls when the server is acknowledging slowly, vs. the
 * ~60 dispatches per 600 ms that caused the original Hunllef freeze-then-die.
 */
private const val MAINTAIN_DISPATCH_COOLDOWN_MS: Long = 250L

/**
 * Per-prayer active-state cache TTL.
 *
 * `Rs2Prayer.isPrayerActive` resolves through `Microbot.getVarbitValue` →
 * `Rs2PlayerStateCache.getVarbitValue`. On a cache miss (first read of a given
 * varbit since login or a `LOGIN_SCREEN` cache flush) it falls through to
 * `Microbot.getClientThread().runOnClientThreadOptional(...)` which BLOCKS
 * the calling thread until the client thread services the request. With a
 * 10 ms poll cadence and the maintenance loop hot-checking the same varbit
 * 100x/sec, every cache miss enqueues a client-thread task. Under freeze
 * conditions (Hunllef prayer-drain bursts, low FPS, GC pauses) the client
 * thread can't service them fast enough and they back up — which is exactly
 * the 10-second freeze the user observed.
 *
 * Local cache: snapshot the answer for 50 ms (≈ 1/12 of a server tick). This
 * is short enough that we still pick up varbit flips within the same tick
 * (the cache is populated by `VarbitChanged` events on the client thread, so
 * once a flip lands the snapshot is invalidated by next-poll TTL expiry),
 * and long enough that 60 polls per server tick collapse to ~12 reads instead
 * of 60.
 */
private const val PRAYER_ACTIVE_CACHE_TTL_MS: Long = 50L

/**
 * Maximum [PendingToggle] entries fired per [tickLogic] invocation. Caps the
 * blast radius if the dispatcher executor stalled (GC, EDT freeze) and a
 * batch of stale toggles all become "ready" simultaneously when it unfreezes.
 *
 * 2 is enough for the worst legal case: a 1-tick-offset Jad rotation needs at
 * most one fire per tick, and Hunllef projectile + transition-anim coincidence
 * needs at most one toggle per damaging event. Any value > 2 in a single tick
 * means the queue accumulated stale entries that should NOT all flush at once.
 */
private const val PENDING_DRAIN_CAP_PER_TICK: Int = 2

private class PvmPrayFlickScript : Script() {

    /**
     * RuneLite [Client] reference, injected by the plugin. We only read
     * [Client.getTickCount] — a simple int accessor safe from the poll
     * thread.
     */
    lateinit var client: Client

    /**
     * Per-NPC cooldown keyed by scene index. Value is the [Client.getTickCount]
     * at which we last scheduled a toggle for that NPC. Guards against
     * lingering animation events from re-registering inside one attack cycle.
     */
    private val attackCooldowns: MutableMap<Int, Int> = ConcurrentHashMap()

    /**
     * Projectile-instance dedup: maps `System.identityHashCode(projectile)`
     * to the tick we first saw it. `ProjectileMoved` fires every tick the
     * projectile is in flight, but we only want to schedule a toggle on the
     * first sighting. We retain the entry until the projectile would have
     * landed (~10 ticks max for Hunllef) so subsequent in-flight events for
     * the same projectile remain dedup'd. Stale entries are pruned by
     * [pruneStaleProjectiles] from the poll loop.
     */
    private val seenProjectiles: MutableMap<Int, Int> = ConcurrentHashMap()

    /**
     * Queue of pending prayer toggles, keyed by NPC scene index. Written by
     * the `@Subscribe` handler (client thread) via [schedulePendingToggle];
     * drained by the poll loop via [tickLogic].
     *
     * Keying by NPC index means a re-animation from the same NPC overwrites
     * the prior pending (shouldn't happen inside the cooldown window, but
     * defensive).
     */
    private val pendingToggles: MutableMap<Int, PendingToggle> = ConcurrentHashMap()

    /**
     * The prayer we most recently activated, if any. Used only by the
     * conservation path to know which prayer to turn off when no boss is
     * mid-attack. Activation itself is idempotent via
     * [Rs2Prayer.isPrayerActive] inside [invokePrayerMenuEntry].
     */
    @Volatile
    private var currentActivePrayer: Rs2PrayerEnum? = null

    /**
     * Maintenance-loop latch for bosses with `BossConfig.maintainPrayer = true`
     * (currently only Hunllef). Set on a stance-transition animation
     * (8754 → PROTECT_MAGIC, 8755 → PROTECT_RANGE) via [setExpectedPrayer];
     * cleared via [clearExpectedPrayer] on NPC despawn or when the poll loop
     * confirms no matching boss is in scope.
     *
     * While non-null, [tickLogic] re-activates this prayer on EVERY poll cycle
     * (10 ms) where `Rs2Prayer.isPrayerActive(it)` is false — which is exactly
     * what defends against Hunllef's prayer-drain projectile (1713/1714)
     * knocking the overhead off mid-fight. The activation is idempotent
     * (short-circuits on already-active), so the only menu dispatches we
     * actually emit are on the tick the prayer just got drained.
     *
     * IMPORTANT: this is the truth source for "what stance is Hunllef in",
     * NOT for "is the prayer up". `Rs2Prayer.isPrayerActive` remains the only
     * truth source for the latter — we never assume the prayer is up just
     * because we set the latch.
     */
    @Volatile
    private var expectedPrayer: Rs2PrayerEnum? = null

    /**
     * Last-emitted-at-millis stamp for the per-prayer maintenance log line.
     * Throttles `[PvmPrayFlick] maintain expected=...` to ~1 Hz so the log
     * doesn't drown when the prayer is drained for multiple consecutive ticks.
     */
    @Volatile
    private var lastMaintainLogMs: Long = 0L

    /**
     * Last-dispatched-at-millis stamp for the maintenance loop's prayer
     * re-activation. Prevents the 10 ms poll from flooding AWT with menu
     * dispatches while the server hasn't yet acknowledged the previous
     * activation (varbit lag = 1+ server tick = 600+ ms). Without this gate
     * the EDT can saturate with 60+ queued menu actions in ~600 ms, freezing
     * the client for 10+ seconds — which is fatal under Hunllef prayer-drain.
     *
     * The reactive scheduler's tick-perfect dispatch path is unaffected — it
     * fires from `pendingToggles` which is naturally rate-limited by the
     * cooldown stamp + pending-toggle dedup.
     */
    @Volatile
    private var lastMaintainDispatchMs: Long = 0L

    /**
     * Mirror of [PvmPrayFlickConfig.conservePrayerPoints]. Default false —
     * prayer stays on between attacks.
     */
    @Volatile
    var conservePoints: Boolean = false

    /**
     * Mirror of [PvmPrayFlickConfig.debugLogging]. Default true — verbose
     * trace logging of listener / matcher / dispatch events.
     */
    @Volatile
    var debugLogging: Boolean = true

    /**
     * Dedicated SINGLE-threaded executor for all script-side polling and
     * dispatch. The base [Script.scheduledExecutorService] is a 10-thread
     * pool, which produced races between workers `-2` and `-6` observed in
     * the field log: one worker called `Rs2Prayer.isPrayerActive` (cache
     * miss → client-thread enqueue), another called the same on the next
     * poll, both of them waiting on the same client-thread queue, etc. This
     * executor serializes everything onto a single thread so contention on
     * `Rs2Prayer.isPrayerActive` and on `Microbot.doInvoke` builds at most
     * one outstanding client-thread task at a time per poll.
     *
     * Thread is daemon + named so it doesn't block JVM shutdown and shows up
     * as `pvmprayflick-dispatch` in stack traces.
     */
    private var dispatchExecutor: ScheduledExecutorService? = null
    private var dispatchFuture: ScheduledFuture<*>? = null

    /**
     * Volatile snapshot of `Rs2Prayer.isPrayerActive(prayer)`, updated by
     * [isPrayerActiveCached]. Indexed by `Rs2PrayerEnum.ordinal` for O(1) read
     * with no boxing.
     *
     * Both arrays are written under no lock — the [PRAYER_ACTIVE_CACHE_TTL_MS]
     * window absorbs torn writes (worst case: one extra dispatch fired against
     * a stale snapshot, which is itself idempotent because `invokePrayer...`
     * short-circuits on the underlying truth).
     */
    private val prayerActiveCache = BooleanArray(Rs2PrayerEnum.values().size)
    private val prayerActiveExpiryMs = LongArray(Rs2PrayerEnum.values().size)

    /**
     * TTL'd snapshot of `Rs2Prayer.isPrayerActive(prayer)`. The first call per
     * prayer per [PRAYER_ACTIVE_CACHE_TTL_MS] window pays the (potentially
     * blocking) client-thread varbit read; subsequent calls within the window
     * return the snapshot. With a 10 ms poll cadence and a 50 ms TTL this
     * collapses ~5 reads into 1, keeping the client thread's queue depth
     * bounded under sustained polling.
     */
    private fun isPrayerActiveCached(prayer: Rs2PrayerEnum): Boolean {
        val idx = prayer.ordinal
        val now = System.currentTimeMillis()
        if (now < prayerActiveExpiryMs[idx]) {
            return prayerActiveCache[idx]
        }
        val fresh = Rs2Prayer.isPrayerActive(prayer)
        prayerActiveCache[idx] = fresh
        prayerActiveExpiryMs[idx] = now + PRAYER_ACTIVE_CACHE_TTL_MS
        return fresh
    }

    /**
     * Forces the next [isPrayerActiveCached] call to re-read from
     * `Rs2Prayer.isPrayerActive`. Called immediately after a dispatch so the
     * maintenance loop's next iteration sees the still-false value (server
     * varbit hasn't flipped yet) and we don't dispatch twice; on the
     * `MAINTAIN_DISPATCH_COOLDOWN_MS` retry path the snapshot is naturally
     * stale by then.
     */
    private fun invalidatePrayerActiveCache(prayer: Rs2PrayerEnum) {
        prayerActiveExpiryMs[prayer.ordinal] = 0L
    }

    /**
     * True iff we've scheduled a toggle for this NPC within the last
     * [cooldownTicks] game ticks. Called by the plugin's @Subscribe handler
     * to drop phantom re-schedules.
     */
    fun isWithinCooldown(npcIndex: Int, nowTick: Int, cooldownTicks: Int): Boolean {
        val last = attackCooldowns[npcIndex] ?: return false
        return nowTick - last < cooldownTicks
    }

    /**
     * Records the cooldown stamp without scheduling a toggle. Used by the
     * "prayer already up at scheduling time" gate so subsequent re-fires
     * from lingering animation events still get dropped by [isWithinCooldown].
     */
    fun stampAttackCooldown(npcIndex: Int, nowTick: Int) {
        attackCooldowns[npcIndex] = nowTick
    }

    /**
     * Records that we've observed a projectile object this tick. Returns
     * `true` if this is the first sighting (caller should schedule a toggle),
     * `false` if we've already scheduled for this projectile instance.
     *
     * Keying on `System.identityHashCode` is safe because we never compare
     * across JVM generations and the underlying client `Projectile` instance
     * is reused for the lifetime of the projectile's flight.
     */
    fun markProjectileSeen(projKey: Int, nowTick: Int): Boolean {
        val prior = seenProjectiles.putIfAbsent(projKey, nowTick)
        // Opportunistically prune entries older than 10 ticks. Hunllef
        // projectiles travel <= ~3 ticks, so anything older is definitely
        // landed/despawned.
        if (prior == null && seenProjectiles.size > 32) {
            pruneStaleProjectiles(nowTick)
        }
        return prior == null
    }

    private fun pruneStaleProjectiles(nowTick: Int) {
        val it = seenProjectiles.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (nowTick - entry.value > 10) it.remove()
        }
    }

    /**
     * Latches the maintenance prayer for a maintain-flagged boss. Called from
     * the plugin's @Subscribe animation handler when a stance-transition anim
     * (8754 / 8755) fires from a maintain-flagged BossConfig.
     *
     * Idempotent on identical re-entries (still logs the transition for
     * observability, but the volatile write is unconditional and lock-free —
     * write-then-publish is fine without a CAS).
     */
    fun setExpectedPrayer(prayer: Rs2PrayerEnum, anim: Int) {
        val prior = expectedPrayer
        expectedPrayer = prayer
        if (prior != prayer) {
            Microbot.log("[PvmPrayFlick] stance set: $anim → $prayer")
        }
    }

    /**
     * Releases the maintenance latch. Called from the plugin's @Subscribe
     * NpcDespawned handler when a maintain-flagged boss leaves the scene, and
     * from the poll loop's "no maintain-flagged boss visible" fallback for
     * cases where the player teleports out without a clean despawn event.
     */
    fun clearExpectedPrayer(reason: String) {
        if (expectedPrayer != null) {
            expectedPrayer = null
            lastMaintainDispatchMs = 0L
            Microbot.log("[PvmPrayFlick] stance cleared ($reason)")
        }
    }

    /**
     * Records a pending toggle from the client-thread @Subscribe handler.
     * Also stamps the cooldown map so re-fires from the same attack cycle
     * are ignored even before the poll gets a chance to drain the pending.
     */
    fun schedulePendingToggle(
        npcIndex: Int,
        npcName: String,
        prayer: Rs2PrayerEnum,
        fireAtTick: Int,
        animationTick: Int,
    ) {
        pendingToggles[npcIndex] = PendingToggle(
            npcIndex = npcIndex,
            npcName = npcName,
            prayer = prayer,
            fireAtTick = fireAtTick,
            animationTick = animationTick,
        )
        attackCooldowns[npcIndex] = animationTick
    }

    /**
     * Kicks off the 10ms polling loop. Named [startScript] because the base
     * [Script] class already defines a zero-arg `run()` used as the
     * per-iteration guard.
     */
    fun startScript(): Boolean {
        Microbot.enableAutoRunOn = false
        // Dedicated single-threaded executor so all polling + dispatch runs
        // on ONE thread. See [dispatchExecutor] KDoc for rationale.
        val ex = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "pvmprayflick-dispatch").apply { isDaemon = true }
        }
        dispatchExecutor = ex
        dispatchFuture = ex.scheduleWithFixedDelay(
            { safeTick() },
            0L,
            10L,
            TimeUnit.MILLISECONDS,
        )
        return true
    }

    /** Wraps [tickLogic] with the logged-in + base-class run() guards. */
    private fun safeTick() {
        try {
            if (!Microbot.isLoggedIn()) return
            if (!baseGuardPasses()) return
            tickLogic()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Microbot.log("[PvmPrayFlick] error: ${e.message}")
        }
    }

    /** Routes through the base Script.run() guard from outside the lambda. */
    private fun baseGuardPasses(): Boolean = super.run()

    /**
     * The entire flicker logic. Every 10ms:
     *   1. Drain [pendingToggles] of any entry whose `fireAtTick <= nowTick`,
     *      firing each toggle via [invokePrayerMenuEntry].
     *   2. **Maintenance loop** (Hunllef): if [expectedPrayer] is latched and
     *      `Rs2Prayer.isPrayerActive` reports it down, dispatch immediately —
     *      no tick scheduling. Defends against Hunllef's prayer-drain
     *      knocking the overhead off mid-stance.
     *   3. Conservation path: if enabled AND no pending toggles are queued
     *      AND no maintenance latch is active AND no matched boss in the
     *      scene is currently animating an attack, deactivate the
     *      last-activated prayer.
     */
    private fun tickLogic() {
        if (!::client.isInitialized) return
        val nowTick = client.tickCount

        // 1. Fire any pending toggles whose scheduled tick has arrived.
        // Iterate once, collect keys-to-remove; sort by fireAtTick so older
        // schedules fire before newer (rarely matters, but deterministic).
        // Hard cap at PENDING_DRAIN_CAP_PER_TICK: if the dispatcher stalled
        // (GC, EDT freeze) and a batch of stale toggles all landed in the
        // same poll, firing them in one burst would saturate the menu queue
        // and re-trigger the freeze. Anything beyond the cap stays queued
        // for the next poll — naturally rate-limits recovery.
        val ready = pendingToggles.values
            .filter { it.fireAtTick <= nowTick }
            .sortedBy { it.fireAtTick }

        var firedThisPoll = 0
        for (p in ready) {
            if (firedThisPoll >= PENDING_DRAIN_CAP_PER_TICK) {
                if (debugLogging) {
                    Microbot.log(
                        "[PvmPrayFlick] drain-cap hit: ${ready.size - firedThisPoll} pending deferred " +
                            "(cap=$PENDING_DRAIN_CAP_PER_TICK) tick=$nowTick"
                    )
                }
                break
            }
            val alreadyActive = isPrayerActiveCached(p.prayer)
            // Skip the actual menu dispatch when prayer is already active;
            // there's nothing for the server to do, and emitting a no-op
            // menu action still costs an AWT post + client-thread hop. This
            // is the path that bit us: alreadyActive=true was logged 60+
            // times per second under prayer-drain bursts.
            if (!alreadyActive) {
                invokePrayerMenuEntryForceInstrumented(p.prayer, "scheduled npc=${p.npcName}")
            }
            currentActivePrayer = p.prayer
            pendingToggles.remove(p.npcIndex)
            firedThisPoll++
            Microbot.log(
                "[PvmPrayFlick] dispatched prayer=${p.prayer} alreadyActive=$alreadyActive " +
                    "for ${p.npcName}#${p.npcIndex} tick=$nowTick (animTick=${p.animationTick}, " +
                    "fireAtTick=${p.fireAtTick})"
            )
        }

        // 2. Maintenance loop. Re-activates the latched stance prayer the
        // INSTANT it's no longer up — the user has already taken damage if we
        // wait a tick. We DO NOT schedule via `pendingToggles` here because
        // that path is intentionally tick-aligned for the FIRST attack of a
        // stance; maintenance is "the prayer just got drained, fix it now".
        // `invokePrayerMenuEntry` short-circuits on already-active so this is
        // a no-op on the vast majority of polls.
        val expected = expectedPrayer
        if (expected != null) {
            if (!isPrayerActiveCached(expected)) {
                // Per-dispatch cooldown gate. The 10 ms poll runs ~60x per
                // server tick; without this gate every poll between "we
                // dispatched" and "the server acknowledged via varbit flip"
                // re-fires invokePrayerMenuEntryForce, saturating AWT and
                // freezing the EDT for 10+ seconds (fatal under Hunllef
                // prayer-drain — observed in the field).
                //
                // Gate semantics:
                //   - Fire if (a) we've never dispatched, OR
                //              (b) MAINTAIN_DISPATCH_COOLDOWN_MS has elapsed
                //                  since the last dispatch (server didn't ack
                //                  → retry).
                //   - On varbit success the outer `!isPrayerActive` check goes
                //     false on the next poll, naturally clearing the rate gate
                //     until the next prayer-drain event.
                val nowMs = System.currentTimeMillis()
                val sinceLastDispatch = nowMs - lastMaintainDispatchMs
                if (lastMaintainDispatchMs == 0L || sinceLastDispatch >= MAINTAIN_DISPATCH_COOLDOWN_MS) {
                    if (nowMs - lastMaintainLogMs >= 1000L) {
                        Microbot.log(
                            "[PvmPrayFlick] maintain expected=$expected active=false → dispatching " +
                                "(sinceLast=${sinceLastDispatch}ms)"
                        )
                        lastMaintainLogMs = nowMs
                    }
                    invokePrayerMenuEntryForceInstrumented(expected, "maintain")
                    lastMaintainDispatchMs = nowMs
                    currentActivePrayer = expected
                }
                // else: in-flight dispatch awaiting server ack; skip this poll.
            } else {
                // Prayer is up — clear the dispatch stamp so the NEXT drain
                // event can fire immediately without waiting out the cooldown
                // from a previous knockoff.
                if (lastMaintainDispatchMs != 0L) {
                    lastMaintainDispatchMs = 0L
                }
            }
            // Self-clearing safety net: if the maintain-flagged boss is no
            // longer in the scene at all (player teleported out / region
            // change / despawn event missed), drop the latch. Cheaper than
            // running this on every poll — gate on the cache having SOME
            // matchable boss-id-set member loaded.
            if (!anyMaintainBossPresent()) {
                clearExpectedPrayer("no maintain-flagged boss in scene")
            }
        }

        // 3. Conservation path. Only runs when enabled AND nothing is queued
        // AND no maintenance latch is active AND no matched boss in the scene
        // is currently mid-attack-animation. The maintenance check is new —
        // without it, conservation would race the maintenance loop and
        // deactivate the prayer we just put up.
        if (conservePoints &&
            pendingToggles.isEmpty() &&
            expectedPrayer == null &&
            !anyMatchedBossAttacking()
        ) {
            val active = currentActivePrayer
            if (active != null && isPrayerActiveCached(active)) {
                Microbot.log(
                    "[PvmPrayFlick] tick=$nowTick deactivating $active (no boss attacking)"
                )
                // Same toggle action flips state off — server treats
                // "Activate" on an active prayer as deactivation. Matches
                // Rs2Prayer.invokePrayer's pattern.
                invokePrayerMenuEntryForceInstrumented(active, "conserve-deactivate")
                currentActivePrayer = null
            }
        }
    }

    /**
     * True iff at least one NPC in the scene matches a maintain-flagged
     * BossConfig. Used by the poll-loop fallback clear path so the latch
     * doesn't survive a region change that didn't fire `NpcDespawned` cleanly
     * (e.g., player teleported out before the despawn event was queued).
     *
     * Iterates the NPC cache via [Rs2NpcQueryable] — same pattern as the
     * conservation path. Cheap; the scene is small and the comparison is an
     * `id in Set<Int>` lookup.
     */
    private fun anyMaintainBossPresent(): Boolean {
        val npcs = Rs2NpcQueryable().toList()
        for (npc in npcs) {
            val cfg = matchBossById(npc.getId(), npc.getName()) ?: continue
            if (cfg.maintainPrayer) return true
        }
        return false
    }

    /**
     * Polled check: is any matched boss in the scene currently showing an
     * attack animation? Only called from the conservation path — the main
     * flicker work doesn't poll the scene at all (it's event-driven).
     */
    private fun anyMatchedBossAttacking(): Boolean {
        val npcs = Rs2NpcQueryable().toList()
        for (npc in npcs) {
            val cfg = matchBossById(npc.getId(), npc.getName()) ?: continue
            val anim = npc.getAnimation()
            if (anim > 0 && cfg.attacks.containsKey(anim)) return true
        }
        return false
    }

    /**
     * Mirror of the plugin-side matcher, used only by the conservation path.
     * Duplicated (rather than shared) because the plugin-side version works
     * on a raw [NPC] (client-thread) and this one on a `Rs2NpcModel`-shaped
     * `(id, name)` pair — sharing would require a wrapper interface for no
     * real gain.
     */
    private fun matchBossById(id: Int, name: String?): BossConfig? {
        for (cfg in BOSSES) {
            if (id in cfg.npcIds) return cfg
            if (name != null && name.contains(cfg.nameKeyword, ignoreCase = true)) return cfg
        }
        return null
    }

    /**
     * Unconditional cursorless prayer toggle. The `(re-check is-active)` step
     * is the caller's responsibility and uses [isPrayerActiveCached] to avoid
     * hammering the varbit cache. In OSRS the "Activate" CC_OP on the prayer
     * widget toggles state, so this dispatch flips the prayer in either
     * direction; `currentActivePrayer` tracking on the call sites keeps the
     * conservation path's toggle-off semantics correct.
     *
     * Menu shape (identical to `Rs2Prayer.invokePrayer`):
     *   - param0     = -1
     *   - param1     = prayer.index  (@Component-annotated widget id)
     *   - opcode     = CC_OP
     *   - identifier = 1
     *   - itemId     = -1
     *   - option     = "Activate"  (toggles both directions)
     *
     * **Instrumentation**: under `debugLogging`, emits a single log line per
     * dispatch with sub-step wall-clock timings. The expected envelope is
     * sub-millisecond for every step. If any step crosses 5+ ms, that's the
     * specific blocker — the log line lets us tell whether the cost was menu
     * construction, `Microbot.doInvoke` itself, or the cached prayer-active
     * read (which lives in the call sites, not here).
     *
     * After dispatch we invalidate the per-prayer cache entry so the NEXT
     * `isPrayerActiveCached` read pays a fresh varbit lookup — this prevents
     * the cache from "lying" about a prayer being inactive after we just
     * toggled it on, which would cause the maintenance loop to re-dispatch.
     */
    private fun invokePrayerMenuEntryForceInstrumented(prayer: Rs2PrayerEnum, source: String) {
        val tStart = System.nanoTime()
        val menuEntry = NewMenuEntry()
            .param0(-1)
            .param1(prayer.index)
            .opcode(MenuAction.CC_OP.id)
            .identifier(1)
            .itemId(-1)
            .option("Activate")
        val tBuilt = System.nanoTime()
        // Rectangle(1, 1) deterministically routes through the (0,0)-origin
        // short-circuit in Rs2UiHelper.getClickingPoint so VirtualMouse.click
        // skips natural-mouse movement. OS cursor never moves.
        Microbot.doInvoke(menuEntry, Rectangle(1, 1))
        val tInvoked = System.nanoTime()
        invalidatePrayerActiveCache(prayer)
        val tEnd = System.nanoTime()

        if (debugLogging) {
            // Convert to micros for a tighter readout — most healthy paths
            // are sub-100 us. Any step > 5_000 us (5 ms) is suspicious; > 50
            // ms means the EDT was actively contended at dispatch time.
            val buildUs = (tBuilt - tStart) / 1_000
            val invokeUs = (tInvoked - tBuilt) / 1_000
            val invalidateUs = (tEnd - tInvoked) / 1_000
            val totalUs = (tEnd - tStart) / 1_000
            Microbot.log(
                "[PvmPrayFlick] dispatch-trace src=$source prayer=$prayer " +
                    "buildUs=$buildUs invokeUs=$invokeUs invalidateUs=$invalidateUs " +
                    "totalUs=$totalUs"
            )
        }
    }

    /**
     * Clean shutdown: cancel the scheduled task (via [Script.shutdown]),
     * clear cooldown + pending-toggle state.
     */
    override fun shutdown() {
        attackCooldowns.clear()
        pendingToggles.clear()
        seenProjectiles.clear()
        currentActivePrayer = null
        expectedPrayer = null
        lastMaintainLogMs = 0L
        lastMaintainDispatchMs = 0L
        for (i in prayerActiveExpiryMs.indices) {
            prayerActiveExpiryMs[i] = 0L
            prayerActiveCache[i] = false
        }
        dispatchFuture?.cancel(false)
        dispatchFuture = null
        dispatchExecutor?.shutdownNow()
        dispatchExecutor = null
        super.shutdown()
    }
}
