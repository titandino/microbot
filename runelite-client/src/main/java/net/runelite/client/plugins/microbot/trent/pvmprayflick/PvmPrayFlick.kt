package net.runelite.client.plugins.microbot.trent.pvmprayflick

import com.google.inject.Provides
import net.runelite.api.Client
import net.runelite.api.MenuAction
import net.runelite.api.NPC
import net.runelite.api.events.AnimationChanged
import net.runelite.api.gameval.AnimationID
import net.runelite.api.gameval.NpcID
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
}

@PluginDescriptor(
    name = PluginDescriptor.Trent + "PVM Prayer Flicker",
    description = "Reactive prayer flicker for GWD + all Jad variants (damage-tick-correct scheduling)",
    tags = ["combat", "prayer", "flicker", "gwd", "godwars", "inferno", "jad", "flick", "ket-rak"],
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
        script.client = client
        script.startScript()
        Microbot.log(
            "[PvmPrayFlick] plugin started (conservePrayerPoints=${script.conservePoints})"
        )
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
        val config = matchBoss(actor) ?: return
        val prayer = config.attacks[actor.animation] ?: return
        val nowTick = client.tickCount

        // Cooldown guard: if we already scheduled a toggle for this NPC this
        // attack cycle, ignore re-fires from lingering animation events.
        if (script.isWithinCooldown(actor.index, nowTick, config.cooldownTicks)) return

        // fireAtTick = T + attackDelayTicks.
        // Toggle invoked at this tick → server applies next tick → prayer
        // active when damage is calculated at T + attackDelayTicks + 1.
        // (Empirical: previous T + attackDelayTicks - 1 offset missed flicks
        // on consecutive 1-tick-offset Jads hitting with different styles.)
        val fireAtTick = nowTick + config.attackDelayTicks
        script.schedulePendingToggle(
            npcIndex = actor.index,
            npcName = actor.name ?: "<unnamed>",
            prayer = prayer,
            fireAtTick = fireAtTick,
            animationTick = nowTick,
        )
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
 */
private data class BossConfig(
    val npcIds: Set<Int>,
    val nameKeyword: String,
    val attackDelayTicks: Int,
    val cooldownTicks: Int,
    val attacks: Map<Int, Rs2PrayerEnum>,
)

/**
 * Bosses handled by this flicker. Ids first, then name fallback. All Jad
 * variants (Fight Caves + Inferno + TzHaar-Ket-Rak) share one entry keyed on
 * the "jad" substring; Ket-Rak challenge Jads with non-enumerated ids are
 * caught by the name fallback.
 */
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
     * Mirror of [PvmPrayFlickConfig.conservePrayerPoints]. Default false —
     * prayer stays on between attacks.
     */
    @Volatile
    var conservePoints: Boolean = false

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
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(
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
     *   2. Conservation path: if enabled AND no pending toggles are queued
     *      AND no matched boss in the scene is currently animating an attack,
     *      deactivate the last-activated prayer.
     */
    private fun tickLogic() {
        if (!::client.isInitialized) return
        val nowTick = client.tickCount

        // 1. Fire any pending toggles whose scheduled tick has arrived.
        // Iterate once, collect keys-to-remove; sort by fireAtTick so older
        // schedules fire before newer (rarely matters, but deterministic).
        val ready = pendingToggles.values
            .filter { it.fireAtTick <= nowTick }
            .sortedBy { it.fireAtTick }

        for (p in ready) {
            invokePrayerMenuEntry(p.prayer)
            currentActivePrayer = p.prayer
            pendingToggles.remove(p.npcIndex)
            Microbot.log(
                "[PvmPrayFlick] tick=$nowTick toggled ${p.prayer} for ${p.npcName}#${p.npcIndex} " +
                    "(animTick=${p.animationTick}, fireAtTick=${p.fireAtTick})"
            )
        }

        // 2. Conservation path. Only runs when enabled AND nothing is queued
        // AND no matched boss in the scene is currently mid-attack-animation.
        if (conservePoints && pendingToggles.isEmpty() && !anyMatchedBossAttacking()) {
            val active = currentActivePrayer
            if (active != null && Rs2Prayer.isPrayerActive(active)) {
                Microbot.log(
                    "[PvmPrayFlick] tick=$nowTick deactivating $active (no boss attacking)"
                )
                // Same toggle action flips state off — server treats
                // "Activate" on an active prayer as deactivation. Matches
                // Rs2Prayer.invokePrayer's pattern.
                invokePrayerMenuEntryForce(active)
                currentActivePrayer = null
            }
        }
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
     * Truly-cursorless prayer toggle via `Microbot.doInvoke(entry, Rectangle(1, 1))`.
     * No-ops if the prayer is already active — avoids emitting an unnecessary
     * synthetic menu event.
     *
     * Menu shape (identical to `Rs2Prayer.invokePrayer`):
     *   - param0     = -1
     *   - param1     = prayer.index  (@Component-annotated widget id)
     *   - opcode     = CC_OP
     *   - identifier = 1
     *   - itemId     = -1
     *   - option     = "Activate"  (toggles both directions)
     */
    private fun invokePrayerMenuEntry(prayer: Rs2PrayerEnum) {
        if (Rs2Prayer.isPrayerActive(prayer)) return
        invokePrayerMenuEntryForce(prayer)
    }

    /**
     * Unconditional variant used by the conservation path to toggle a
     * currently-active prayer off. In OSRS the "Activate" CC_OP on the
     * prayer widget toggles state, so this is the same dispatch as
     * [invokePrayerMenuEntry] without the `isPrayerActive` short-circuit.
     */
    private fun invokePrayerMenuEntryForce(prayer: Rs2PrayerEnum) {
        val menuEntry = NewMenuEntry()
            .param0(-1)
            .param1(prayer.index)
            .opcode(MenuAction.CC_OP.id)
            .identifier(1)
            .itemId(-1)
            .option("Activate")
        // Rectangle(1, 1) deterministically routes through the (0,0)-origin
        // short-circuit in Rs2UiHelper.getClickingPoint so VirtualMouse.click
        // skips natural-mouse movement. OS cursor never moves.
        Microbot.doInvoke(menuEntry, Rectangle(1, 1))
    }

    /**
     * Clean shutdown: cancel the scheduled task (via [Script.shutdown]),
     * clear cooldown + pending-toggle state.
     */
    override fun shutdown() {
        attackCooldowns.clear()
        pendingToggles.clear()
        currentActivePrayer = null
        super.shutdown()
    }
}
