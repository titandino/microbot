package net.runelite.client.plugins.microbot.trent.nightmare

import com.google.inject.Provides
import net.runelite.api.ChatMessageType
import net.runelite.api.Client
import net.runelite.api.GameObject
import net.runelite.api.MenuAction
import net.runelite.api.NPC
import net.runelite.api.Perspective
import net.runelite.api.coords.WorldPoint
import net.runelite.api.events.AnimationChanged
import net.runelite.api.events.ChatMessage
import net.runelite.api.events.GameObjectDespawned
import net.runelite.api.events.GameObjectSpawned
import net.runelite.api.events.GameTick
import net.runelite.api.events.NpcChanged
import net.runelite.api.events.NpcDespawned
import net.runelite.api.events.NpcSpawned
import net.runelite.api.gameval.AnimationID
import net.runelite.api.gameval.NpcID
import net.runelite.api.gameval.SpotanimID
import net.runelite.client.callback.ClientThread
import net.runelite.client.config.Config
import net.runelite.client.config.ConfigGroup
import net.runelite.client.config.ConfigItem
import net.runelite.client.config.ConfigManager
import net.runelite.client.eventbus.Subscribe
import net.runelite.client.game.SpriteManager
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum
import net.runelite.client.ui.overlay.Overlay
import net.runelite.client.ui.overlay.OverlayLayer
import net.runelite.client.ui.overlay.OverlayManager
import net.runelite.client.ui.overlay.OverlayPosition
import net.runelite.client.ui.overlay.OverlayUtil
import net.runelite.client.ui.overlay.components.ComponentConstants
import net.runelite.client.ui.overlay.components.ImageComponent
import net.runelite.client.ui.overlay.components.LineComponent
import net.runelite.client.ui.overlay.components.PanelComponent
import net.runelite.client.ui.overlay.components.TitleComponent
import org.slf4j.LoggerFactory
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics2D
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@ConfigGroup("trentnightmare")
interface NightmareConfig : Config {
    @ConfigItem(
        keyName = "autoFlick",
        name = "Auto-flick prayer",
        description = "Automatically flip protect prayers based on the boss's attack animation",
        position = 0,
    )
    fun autoFlick(): Boolean = false

    @ConfigItem(
        keyName = "prayerHelper",
        name = "Prayer suggestion overlay",
        description = "Show the recommended prayer in the bottom-right corner",
        position = 1,
    )
    fun prayerHelper(): Boolean = true

    @ConfigItem(
        keyName = "tickCounter",
        name = "Tick counter overlay",
        description = "Show the number of ticks until the next attack above the boss",
        position = 2,
    )
    fun tickCounter(): Boolean = true

    @ConfigItem(
        keyName = "floorOverlays",
        name = "Floor overlays (cheaty mode)",
        description = "Highlights rifts, mushrooms, husks, sleepwalkers, parasites, and totems on the arena floor",
        position = 3,
    )
    fun floorOverlays(): Boolean = true

    @ConfigItem(
        keyName = "statusHud",
        name = "Status HUD",
        description = "Show a status panel with phase, next attack, parasite countdown, and add counts",
        position = 4,
    )
    fun statusHud(): Boolean = true

    @ConfigItem(
        keyName = "riftHighlightTicks",
        name = "Rift highlight duration",
        description = "How many ticks (after spawn) to keep the orange grasping-claws tile lit. The damage window is roughly 3 ticks; lower this if the highlight outstays its welcome.",
        position = 5,
    )
    fun riftHighlightTicks(): Int = 3

    @ConfigItem(
        keyName = "debugLogging",
        name = "Debug logging",
        description = "Verbose SLF4J logging for boss capture, attack detection, and prayer dispatch",
        position = 6,
    )
    fun debugLogging(): Boolean = false
}

/**
 * Each row maps an attack-animation id under a curse state to the protect
 * prayer it demands. Sprite ids 127/128/129 mirror the OpenOSRS overlay so the
 * BR panel paints the same icon the player would see in the prayer book.
 */
enum class NightmareAttack(
    val animation: Int,
    val prayer: Rs2PrayerEnum,
    val tickColor: Color,
    val prayerSpriteId: Int,
    val displayName: String,
) {
    MELEE(AnimationID.NIGHTMARE_ATTACK_MELEE, Rs2PrayerEnum.PROTECT_MELEE, Color.RED, 129, "Melee"),
    MAGIC(AnimationID.NIGHTMARE_ATTACK_MAGIC, Rs2PrayerEnum.PROTECT_MAGIC, Color.CYAN, 127, "Magic"),
    RANGE(AnimationID.NIGHTMARE_ATTACK_RANGED, Rs2PrayerEnum.PROTECT_RANGE, Color.GREEN, 128, "Ranged"),

    // Curse shuffles pray-target one slot to the right of the displayed style:
    // melee anim → wear missiles, magic anim → wear melee, ranged anim → wear magic.
    CURSE_MELEE(AnimationID.NIGHTMARE_ATTACK_MELEE, Rs2PrayerEnum.PROTECT_RANGE, Color.GREEN, 128, "Melee (cursed)"),
    CURSE_MAGIC(AnimationID.NIGHTMARE_ATTACK_MAGIC, Rs2PrayerEnum.PROTECT_MELEE, Color.RED, 129, "Magic (cursed)"),
    CURSE_RANGE(AnimationID.NIGHTMARE_ATTACK_RANGED, Rs2PrayerEnum.PROTECT_MAGIC, Color.CYAN, 127, "Ranged (cursed)"),
    ;

    companion object {
        fun forAnimation(animId: Int, cursed: Boolean): NightmareAttack? = when (animId) {
            AnimationID.NIGHTMARE_ATTACK_MELEE -> if (cursed) CURSE_MELEE else MELEE
            AnimationID.NIGHTMARE_ATTACK_MAGIC -> if (cursed) CURSE_MAGIC else MAGIC
            AnimationID.NIGHTMARE_ATTACK_RANGED -> if (cursed) CURSE_RANGE else RANGE
            else -> null
        }
    }
}

/**
 * Boss-NPC ids that should be considered "the Nightmare boss" for state tracking.
 * Includes every phase / weak / blast / desperation form for both group and Phosani's
 * variants. We capture on ANY of these — the previous version only watched the
 * INITIAL ids and missed re-entry into an in-progress instance, which was the
 * primary cause of "auto-flick does nothing."
 */
private val BOSS_IDS: Set<Int> = setOf(
    NpcID.NIGHTMARE_INITIAL,                 // 9432
    NpcID.NIGHTMARE_PHASE_01,                // 9425
    NpcID.NIGHTMARE_PHASE_02,                // 9426
    NpcID.NIGHTMARE_PHASE_03,                // 9427
    NpcID.NIGHTMARE_WEAK_PHASE_01,           // 9428
    NpcID.NIGHTMARE_WEAK_PHASE_02,           // 9429
    NpcID.NIGHTMARE_WEAK_PHASE_03,           // 9430
    NpcID.NIGHTMARE_BLAST,                   // 9431
    NpcID.NIGHTMARE_CHALLENGE_INITIAL,       // 9423
    NpcID.NIGHTMARE_CHALLENGE_PHASE_01,      // 9416
    NpcID.NIGHTMARE_CHALLENGE_PHASE_02,      // 9417
    NpcID.NIGHTMARE_CHALLENGE_PHASE_03,      // 9418
    NpcID.NIGHTMARE_CHALLENGE_PHASE_04,      // 11153 (desperation)
    NpcID.NIGHTMARE_CHALLENGE_PHASE_05,      // 11154
    NpcID.NIGHTMARE_CHALLENGE_WEAK_PHASE_01, // 9419
    NpcID.NIGHTMARE_CHALLENGE_WEAK_PHASE_02, // 9420
    NpcID.NIGHTMARE_CHALLENGE_WEAK_PHASE_03, // 9421
    NpcID.NIGHTMARE_CHALLENGE_WEAK_PHASE_04, // 11155
    NpcID.NIGHTMARE_CHALLENGE_BLAST,         // 9422
)

private val DYING_IDS: Set<Int> = setOf(NpcID.NIGHTMARE_DYING, NpcID.NIGHTMARE_CHALLENGE_DYING)

/** Maps a boss NPC id to a human-readable phase label for the status HUD. */
private val PHASE_LABELS: Map<Int, String> = mapOf(
    NpcID.NIGHTMARE_INITIAL to "Sleeping",
    NpcID.NIGHTMARE_PHASE_01 to "Phase 1",
    NpcID.NIGHTMARE_PHASE_02 to "Phase 2",
    NpcID.NIGHTMARE_PHASE_03 to "Phase 3",
    NpcID.NIGHTMARE_WEAK_PHASE_01 to "Weak P1 (totems)",
    NpcID.NIGHTMARE_WEAK_PHASE_02 to "Weak P2 (totems)",
    NpcID.NIGHTMARE_WEAK_PHASE_03 to "Weak P3 (totems)",
    NpcID.NIGHTMARE_BLAST to "Blast",
    NpcID.NIGHTMARE_CHALLENGE_INITIAL to "Sleeping",
    NpcID.NIGHTMARE_CHALLENGE_PHASE_01 to "Phase 1",
    NpcID.NIGHTMARE_CHALLENGE_PHASE_02 to "Phase 2",
    NpcID.NIGHTMARE_CHALLENGE_PHASE_03 to "Phase 3",
    NpcID.NIGHTMARE_CHALLENGE_PHASE_04 to "Desperation",
    NpcID.NIGHTMARE_CHALLENGE_PHASE_05 to "Desperation+",
    NpcID.NIGHTMARE_CHALLENGE_WEAK_PHASE_01 to "Weak P1 (totems)",
    NpcID.NIGHTMARE_CHALLENGE_WEAK_PHASE_02 to "Weak P2 (totems)",
    NpcID.NIGHTMARE_CHALLENGE_WEAK_PHASE_03 to "Weak P3 (totems)",
    NpcID.NIGHTMARE_CHALLENGE_WEAK_PHASE_04 to "Weak P4 (totems)",
    NpcID.NIGHTMARE_CHALLENGE_BLAST to "Blast",
)

private val HUSK_RANGED_IDS: Set<Int> = setOf(
    NpcID.NIGHTMARE_HUSK_RANGED,             // 9455
    NpcID.NIGHTMARE_CHALLENGE_HUSK_RANGED,   // 9467
)
private val HUSK_MAGIC_IDS: Set<Int> = setOf(
    NpcID.NIGHTMARE_HUSK_MAGIC,              // 9454
    NpcID.NIGHTMARE_CHALLENGE_HUSK_MAGIC,    // 9466
)
private val SLEEPWALKER_IDS: Set<Int> = setOf(
    NpcID.NIGHTMARE_SLEEPWALKER_1,           // 9446
    NpcID.NIGHTMARE_SLEEPWALKER_2,           // 9447
    NpcID.NIGHTMARE_SLEEPWALKER_3,           // 9448
    NpcID.NIGHTMARE_SLEEPWALKER_4,           // 9449
    NpcID.NIGHTMARE_SLEEPWALKER_5,           // 9450
    NpcID.NIGHTMARE_SLEEPWALKER_6,           // 9451
    NpcID.NIGHTMARE_CHALLENGE_SLEEPWALKER,   // 9470
)
private val PARASITE_STRONG_IDS: Set<Int> = setOf(
    NpcID.NIGHTMARE_PARASITE,                // 9452
    NpcID.NIGHTMARE_CHALLENGE_PARASITE,      // 9468
)
private val PARASITE_WEAK_IDS: Set<Int> = setOf(
    NpcID.NIGHTMARE_PARASITE_WEAK,           // 9453
    NpcID.NIGHTMARE_CHALLENGE_PARASITE_WEAK, // 9469
)

/** Mushroom GameObject id (NIGHTMARE_MUSHROOM in the OpenOSRS plugin). */
private const val NIGHTMARE_MUSHROOM_OBJECT_ID = 37739

/**
 * Totem phase mapping. Orange = dormant, green = ready/active, red = charged.
 * Mirrored from the OpenOSRS `TotemPhase` enum.
 */
private val TOTEM_DORMANT_IDS: Set<Int> = setOf(9434, 9437, 9440, 9443)
private val TOTEM_READY_IDS: Set<Int> = setOf(9435, 9438, 9441, 9444)
private val TOTEM_CHARGED_IDS: Set<Int> = setOf(9436, 9439, 9442, 9445)
private val ALL_TOTEM_IDS: Set<Int> = TOTEM_DORMANT_IDS + TOTEM_READY_IDS + TOTEM_CHARGED_IDS

private fun totemColor(id: Int): Color? = when (id) {
    in TOTEM_DORMANT_IDS -> Color.ORANGE
    in TOTEM_READY_IDS -> Color.GREEN
    in TOTEM_CHARGED_IDS -> Color.RED
    else -> null
}

/** Phosani parasite-burst window (~18 ticks per wiki). */
private const val PARASITE_BURST_TICKS = 18

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Nightmare",
    description = "Cheaty floor overlays, prayer suggestion, tick counter, and opt-in auto-flick for The Nightmare / Phosani's Nightmare",
    tags = ["pvm", "prayer", "nightmare", "phosani"],
    enabledByDefault = false,
)
class Nightmare : Plugin() {

    /**
     * SLF4J logger. Used in @Subscribe handlers (which run on the client thread)
     * via `log.debug` only — never `Microbot.log` from the client thread, since
     * the chat appender re-enters the client thread synchronously and can stall.
     */
    private val log = LoggerFactory.getLogger(Nightmare::class.java)

    @Inject
    private lateinit var config: NightmareConfig

    @Provides
    fun provideConfig(configManager: ConfigManager): NightmareConfig =
        configManager.getConfig(NightmareConfig::class.java)

    @Inject
    private lateinit var client: Client

    @Inject
    private lateinit var clientThread: ClientThread

    @Inject
    private lateinit var overlayManager: OverlayManager

    @Inject
    private lateinit var spriteManager: SpriteManager

    private val prayerOverlay = NightmarePrayerOverlay()
    private val tickOverlay = NightmareTickOverlay()
    private val floorOverlay = NightmareFloorOverlay()
    private val statusOverlay = NightmareStatusOverlay()

    @Volatile
    var nm: NPC? = null
        private set

    @Volatile
    var inFight: Boolean = false
        private set

    @Volatile
    var pendingAttack: NightmareAttack? = null
        private set

    @Volatile
    var ticksUntilNextAttack: Int = 0
        private set

    @Volatile
    private var cursed: Boolean = false

    @Volatile
    private var attacksSinceCurse: Int = 0

    @Volatile
    var phaseLabel: String = ""
        private set

    /** Ticks remaining before the impregnated parasite bursts; 0 = no parasite. */
    @Volatile
    var parasiteBurstTicks: Int = 0
        private set

    /** Tracks all mushroom GameObjects currently spawned in scene. */
    val mushrooms: MutableSet<GameObject> =
        Collections.newSetFromMap(ConcurrentHashMap<GameObject, Boolean>())

    /** Background poll loop control. */
    @Volatile
    private var running: Boolean = false
    private var executor: ScheduledExecutorService? = null
    private var pollFuture: ScheduledFuture<*>? = null

    override fun startUp() {
        overlayManager.add(prayerOverlay)
        overlayManager.add(tickOverlay)
        overlayManager.add(floorOverlay)
        overlayManager.add(statusOverlay)
        running = true
        // Single-threaded executor so we don't race with ourselves between polls.
        val ex = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "trent-nightmare-flick").apply { isDaemon = true }
        }
        executor = ex
        pollFuture = ex.scheduleWithFixedDelay({ safePoll() }, 0L, 60L, TimeUnit.MILLISECONDS)
        log.info("[Nightmare] plugin started (autoFlick={}, debugLogging={})", config.autoFlick(), config.debugLogging())
    }

    override fun shutDown() {
        running = false
        pollFuture?.cancel(false)
        pollFuture = null
        executor?.shutdownNow()
        executor = null
        overlayManager.remove(prayerOverlay)
        overlayManager.remove(tickOverlay)
        overlayManager.remove(floorOverlay)
        overlayManager.remove(statusOverlay)
        reset()
        mushrooms.clear()
        log.info("[Nightmare] plugin stopped")
    }

    private fun reset() {
        nm = null
        inFight = false
        pendingAttack = null
        ticksUntilNextAttack = 0
        cursed = false
        attacksSinceCurse = 0
        phaseLabel = ""
        parasiteBurstTicks = 0
    }

    @Subscribe
    fun onAnimationChanged(event: AnimationChanged) {
        val actor = event.actor ?: return

        // Player-impregnation timer: when the LOCAL player plays the impregnate
        // animation, start the 18-tick parasite-burst countdown.
        val localPlayer = client.localPlayer
        if (localPlayer != null && actor === localPlayer) {
            if (localPlayer.animation == AnimationID.NIGHTMARE_PARASITE_IMPREGNATE_PLAYER) {
                parasiteBurstTicks = PARASITE_BURST_TICKS
                if (config.debugLogging()) {
                    log.info("[Nightmare] parasite impregnation detected on local player; burst in {}t", PARASITE_BURST_TICKS)
                }
            }
            return
        }

        if (actor !is NPC) return
        val anim = actor.animation
        if (anim != AnimationID.NIGHTMARE_ATTACK_MELEE &&
            anim != AnimationID.NIGHTMARE_ATTACK_MAGIC &&
            anim != AnimationID.NIGHTMARE_ATTACK_RANGED
        ) {
            return
        }

        // Accept anim from EITHER the captured boss reference OR any actor whose
        // id is in BOSS_IDS — the captured ref can drift across phase swaps even
        // when no NpcChanged fires for the swap.
        val captured = nm
        val isCaptured = captured != null && actor === captured
        val isBossId = actor.id in BOSS_IDS
        if (!isCaptured && !isBossId) return

        // If we drifted onto a different NPC ref but the id matches, refresh.
        if (!isCaptured && isBossId) {
            nm = actor
            inFight = true
            if (config.debugLogging()) {
                log.info("[Nightmare] boss captured via animation drift: id={} name='{}' anim={}", actor.id, actor.name, anim)
            }
        }

        val attack = NightmareAttack.forAnimation(anim, cursed)
        pendingAttack = attack
        ticksUntilNextAttack = 7

        if (config.debugLogging()) {
            log.info(
                "[Nightmare] attack-anim id={} cursed={} -> prayer={} (anim={})",
                actor.id, cursed, attack?.prayer, anim,
            )
        }

        if (cursed) {
            attacksSinceCurse++
            if (attacksSinceCurse >= 5) {
                cursed = false
                attacksSinceCurse = 0
                if (config.debugLogging()) {
                    log.info("[Nightmare] curse counter expired (5 attacks)")
                }
            }
        }
    }

    @Subscribe
    fun onChatMessage(event: ChatMessage) {
        if (event.type != ChatMessageType.GAMEMESSAGE) return
        val msg = event.message
        when {
            msg.contains("cursed you, shuffling your prayers") -> {
                cursed = true
                attacksSinceCurse = 0
                if (config.debugLogging()) log.info("[Nightmare] curse ENTERED via chat")
            }
            msg.contains("Nightmare's curse wear off") -> {
                cursed = false
                attacksSinceCurse = 0
                if (config.debugLogging()) log.info("[Nightmare] curse EXITED via chat")
            }
        }
    }

    @Subscribe
    fun onNpcSpawned(event: NpcSpawned) {
        val npc = event.npc ?: return
        if (npc.id in BOSS_IDS) {
            nm = npc
            inFight = true
            phaseLabel = PHASE_LABELS[npc.id] ?: ""
            if (config.debugLogging()) {
                log.info(
                    "[Nightmare] boss captured via spawn: id={} name='{}' loc={} phase='{}'",
                    npc.id, npc.name, npc.worldLocation, phaseLabel,
                )
            }
        } else if (npc.id in PARASITE_STRONG_IDS || npc.id in PARASITE_WEAK_IDS) {
            if (config.debugLogging()) {
                log.info("[Nightmare] parasite NPC spawned: id={} loc={}", npc.id, npc.worldLocation)
            }
        }
    }

    @Subscribe
    fun onNpcDespawned(event: NpcDespawned) {
        if (event.npc === nm) {
            if (config.debugLogging()) {
                log.info("[Nightmare] boss despawned (id={}); resetting", event.npc.id)
            }
            reset()
        }
    }

    @Subscribe
    fun onNpcChanged(event: NpcChanged) {
        val npc = event.npc ?: return
        val boss = nm
        if (npc === boss) {
            if (npc.id in DYING_IDS) {
                if (config.debugLogging()) log.info("[Nightmare] boss is dying; resetting")
                reset()
                return
            }
            inFight = true
            phaseLabel = PHASE_LABELS[npc.id] ?: phaseLabel
            if (config.debugLogging()) {
                log.info("[Nightmare] boss phase change: id={} phase='{}'", npc.id, phaseLabel)
            }
        } else if (boss == null && npc.id in BOSS_IDS) {
            // Initial wake-up goes 9432 → 9425 (or 9423 → 9416) on a definition
            // change rather than a separate spawn event in some instances.
            // Capture here as a fallback.
            nm = npc
            inFight = true
            phaseLabel = PHASE_LABELS[npc.id] ?: ""
            if (config.debugLogging()) {
                log.info(
                    "[Nightmare] boss captured via NpcChanged fallback: id={} phase='{}'",
                    npc.id, phaseLabel,
                )
            }
        }
    }

    @Subscribe
    fun onGameTick(event: GameTick) {
        // Gate on nm != null (NOT region) — instanced regions get dynamic ids
        // and the static 15256/15258 check missed re-entries.
        if (nm == null) {
            if (inFight) {
                if (config.debugLogging()) log.debug("[Nightmare] no boss in scope; clearing inFight")
                reset()
            }
            return
        }
        if (ticksUntilNextAttack > 0) {
            ticksUntilNextAttack--
            if (ticksUntilNextAttack == 0) {
                pendingAttack = null
            }
        }
        if (parasiteBurstTicks > 0) {
            parasiteBurstTicks--
        }
    }

    @Subscribe
    fun onGameObjectSpawned(event: GameObjectSpawned) {
        val obj = event.gameObject ?: return
        if (obj.id == NIGHTMARE_MUSHROOM_OBJECT_ID) {
            mushrooms.add(obj)
            if (config.debugLogging()) {
                log.info("[Nightmare] mushroom spawned at {} (total={})", obj.worldLocation, mushrooms.size)
            }
        }
    }

    @Subscribe
    fun onGameObjectDespawned(event: GameObjectDespawned) {
        val obj = event.gameObject ?: return
        if (obj.id == NIGHTMARE_MUSHROOM_OBJECT_ID) {
            mushrooms.remove(obj)
        }
    }

    /**
     * Background poll. Lives off the client thread; only reads volatile state
     * and dispatches prayer toggles via the client-thread invoke helper. The
     * Nightmare's 7-tick cycle is generous enough that 60ms poll cadence is
     * more than fast enough.
     */
    private fun safePoll() {
        try {
            if (!running) return
            if (!Microbot.isLoggedIn()) return
            if (config.autoFlick() && nm != null) {
                handleAutoFlick()
            }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            running = false
        } catch (t: Throwable) {
            log.warn("[Nightmare] poll error", t)
        }
    }

    private fun handleAutoFlick() {
        val target = pendingAttack?.prayer ?: return

        // Off the wrong overheads first so we never have two on at once.
        for (other in PROTECT_PRAYERS) {
            if (other != target && Rs2Prayer.isPrayerActive(other)) {
                flipPrayer(other, on = false)
            }
        }
        // Then the correct one.
        if (!Rs2Prayer.isPrayerActive(target)) {
            flipPrayer(target, on = true)
        }
    }

    /**
     * Robust prayer flip. Tries Rs2Prayer.toggle first (which posts via
     * Microbot.doInvoke + AWT and waits up to 10s for the varbit ack); if the
     * state still hasn't flipped, falls back to a direct client.menuAction
     * dispatch on the client thread — same pattern as PvmPrayFlick.kt
     * (project memory: "Direct client.menuAction dispatch for lowest-latency
     * invocation").
     *
     * **Hard rule:** if both fail, log error and give up. Never reach for the
     * mouse (`Rs2Widget.clickWidget` / `Microbot.getMouse().click`) per the
     * "No hardware-mouse fallback for prayer automation" project memory.
     */
    private fun flipPrayer(target: Rs2PrayerEnum, on: Boolean) {
        if (Rs2Prayer.isPrayerActive(target) == on) return

        if (config.debugLogging()) {
            log.info("[Nightmare] flipPrayer target={} on={} (currently active={})", target, on, Rs2Prayer.isPrayerActive(target))
        }

        // Path 1: high-level toggle. Rs2Prayer.toggle internally calls
        // Microbot.doInvoke and sleeps up to 10s waiting for the varbit. Since
        // we're on a background thread that's fine; the Nightmare's 7-tick
        // (4.2s) cycle has plenty of headroom.
        val ok = try {
            Rs2Prayer.toggle(target, on)
        } catch (t: Throwable) {
            log.warn("[Nightmare] Rs2Prayer.toggle threw for {}: {}", target, t.message)
            false
        }

        if (ok && Rs2Prayer.isPrayerActive(target) == on) {
            if (config.debugLogging()) {
                log.info("[Nightmare] flipPrayer OK via Rs2Prayer.toggle target={} on={}", target, on)
            }
            return
        }

        // Path 2: direct client.menuAction dispatch on the client thread. Same
        // 7-arg signature stock RuneLite plugins use.
        if (config.debugLogging()) {
            log.info("[Nightmare] Rs2Prayer.toggle didn't flip; falling back to direct client.menuAction for {}", target)
        }
        val entry = NewMenuEntry()
            .param0(-1)
            .param1(target.index)
            .opcode(MenuAction.CC_OP.id)
            .identifier(1)
            .itemId(-1)
            .option("Activate")
        val p0 = entry.param0
        val p1 = entry.param1
        val identifier = entry.identifier
        val localClient = client
        // Runnable wrapping resolves Kotlin SAM ambiguity between
        // ClientThread.invoke(Runnable) and the BooleanSupplier overload.
        clientThread.invoke(Runnable {
            try {
                localClient.menuAction(
                    /* p0         */ p0,
                    /* p1         */ p1,
                    /* action     */ MenuAction.CC_OP,
                    /* identifier */ identifier,
                    /* itemId     */ -1,
                    /* option     */ "Activate",
                    /* target     */ "",
                )
            } catch (e: Exception) {
                log.warn("[Nightmare] direct client.menuAction failed for {}: {}", target, e.message)
            }
        })

        // We don't poll for the varbit here — the next handleAutoFlick() call
        // (60ms later) will see the result and either short-circuit or retry.
    }

    /**
     * Translucent panel in BR corner showing the recommended protect prayer.
     * Background flips red when the player doesn't currently have the prayer
     * up — visual nag that they're about to eat the next hit.
     */
    private inner class NightmarePrayerOverlay : Overlay() {
        private val panel = PanelComponent()
        private val notActivatedBackground = Color(150, 0, 0, 150)

        init {
            position = OverlayPosition.BOTTOM_RIGHT
            layer = OverlayLayer.ABOVE_WIDGETS
        }

        override fun render(graphics: Graphics2D): Dimension? {
            if (!config.prayerHelper()) return null
            if (!inFight) return null
            val attack = pendingAttack ?: return null
            val sprite = spriteManager.getSprite(attack.prayerSpriteId, 0)
            if (sprite == null) {
                if (config.debugLogging()) {
                    log.info("[Nightmare] prayer-overlay: sprite {} not loaded yet", attack.prayerSpriteId)
                }
                return null
            }

            val active = Rs2Prayer.isPrayerActive(attack.prayer)
            panel.children.clear()
            panel.backgroundColor = if (active) ComponentConstants.STANDARD_BACKGROUND_COLOR else notActivatedBackground
            panel.children.add(ImageComponent(sprite))
            return panel.render(graphics)
        }
    }

    /**
     * Number floating above the boss showing remaining ticks until the next
     * attack lands.
     */
    private inner class NightmareTickOverlay : Overlay() {
        init {
            position = OverlayPosition.DYNAMIC
            layer = OverlayLayer.ABOVE_SCENE
        }

        override fun render(graphics: Graphics2D): Dimension? {
            if (!config.tickCounter()) return null
            if (!inFight) return null
            val ticks = ticksUntilNextAttack
            if (ticks <= 0) return null
            val attack = pendingAttack ?: return null
            val boss = nm ?: return null
            val bossLoc = boss.localLocation ?: return null

            val text = ticks.toString()
            val location = Perspective.getCanvasTextLocation(client, graphics, bossLoc, text, 0) ?: return null

            val color = if (ticks >= 4) attack.tickColor else Color.WHITE
            val originalFont = graphics.font
            graphics.font = originalFont.deriveFont(Font.BOLD, 20f)
            OverlayUtil.renderTextLocation(graphics, location, text, color)
            graphics.font = originalFont
            return null
        }
    }

    /**
     * The "cheaty" floor overlay. Highlights:
     *  - Rift / Grasping-Claw shadow tiles (orange) — DO NOT walk here.
     *  - Mushroom GameObjects (yellow, 1-tile radius) — do not step within 1 tile.
     *  - Husks (red ranged / cyan magic) — kill ranged first.
     *  - Sleepwalkers (red/yellow/green by distance to boss) with line to boss.
     *  - Parasite NPCs (purple strong / green weak).
     *  - Totems (orange dormant / green ready / red charged).
     */
    private inner class NightmareFloorOverlay : Overlay() {
        private val rifFillColor = Color(255, 140, 0, 90) // translucent orange fill
        private val mushroomFillColor = Color(255, 255, 0, 70)
        private val outlineStroke = BasicStroke(2f)
        private val thickStroke = BasicStroke(3f)
        private val sleepwalkerLineStroke = BasicStroke(1.5f)

        init {
            position = OverlayPosition.DYNAMIC
            layer = OverlayLayer.ABOVE_SCENE
        }

        override fun render(graphics: Graphics2D): Dimension? {
            if (!config.floorOverlays()) return null
            if (nm == null) return null

            renderRifts(graphics)
            renderMushrooms(graphics)
            renderHusks(graphics)
            renderSleepwalkers(graphics)
            renderParasites(graphics)
            renderTotems(graphics)
            return null
        }

        private fun renderRifts(graphics: Graphics2D) {
            val wv = client.topLevelWorldView ?: return
            // 30 client cycles per server tick (1 tick = 600ms, 1 cycle = 20ms).
            // GraphicsObject.startCycle is when the spotanim spawned; the visual outlives
            // the actual damage window, so cap the highlight at the configured tick count.
            val cap = config.riftHighlightTicks().coerceIn(1, 10)
            val now = client.gameCycle
            for (go in wv.graphicsObjects) {
                if (go.id != SpotanimID.NIGHTMARE_RIFT) continue
                val ticksAlive = (now - go.startCycle) / 30
                if (ticksAlive > cap) continue
                val lp = go.location ?: continue
                val poly = Perspective.getCanvasTilePoly(client, lp) ?: continue
                OverlayUtil.renderPolygon(graphics, poly, Color.ORANGE, rifFillColor, thickStroke)
            }
        }

        private fun renderMushrooms(graphics: Graphics2D) {
            for (m in mushrooms) {
                val lp = m.localLocation
                val poly = Perspective.getCanvasTilePoly(client, lp) ?: continue
                OverlayUtil.renderPolygon(graphics, poly, Color.YELLOW, mushroomFillColor, outlineStroke)
            }
        }

        private fun renderHusks(graphics: Graphics2D) {
            for (npc in npcsInScene()) {
                val color = when (npc.id) {
                    in HUSK_RANGED_IDS -> Color.RED
                    in HUSK_MAGIC_IDS -> Color.CYAN
                    else -> continue
                }
                val hull = npc.convexHull ?: continue
                graphics.color = color
                val originalStroke = graphics.stroke
                graphics.stroke = thickStroke
                graphics.draw(hull)
                graphics.stroke = originalStroke
            }
        }

        private fun renderSleepwalkers(graphics: Graphics2D) {
            val boss = nm ?: return
            val bossLoc = boss.worldLocation ?: return
            val bossLocalLoc = boss.localLocation
            val plane = client.topLevelWorldView?.plane ?: return
            for (npc in npcsInScene()) {
                if (npc.id !in SLEEPWALKER_IDS) continue
                val swLoc = npc.worldLocation ?: continue
                val distance = bossLoc.distanceTo(swLoc)
                val color = when {
                    distance <= 2 -> Color.RED
                    distance <= 5 -> Color.YELLOW
                    else -> Color.GREEN
                }
                val hull = npc.convexHull
                if (hull != null) {
                    graphics.color = color
                    val originalStroke = graphics.stroke
                    graphics.stroke = outlineStroke
                    graphics.draw(hull)
                    graphics.stroke = originalStroke
                }
                // Path line to boss.
                val swLocal = npc.localLocation
                if (swLocal != null && bossLocalLoc != null) {
                    val swPoint = Perspective.localToCanvas(client, swLocal, plane)
                    val bossPoint = Perspective.localToCanvas(client, bossLocalLoc, plane)
                    if (swPoint != null && bossPoint != null) {
                        graphics.color = Color(color.red, color.green, color.blue, 120)
                        val originalStroke = graphics.stroke
                        graphics.stroke = sleepwalkerLineStroke
                        graphics.drawLine(swPoint.x, swPoint.y, bossPoint.x, bossPoint.y)
                        graphics.stroke = originalStroke
                    }
                }
            }
        }

        private fun renderParasites(graphics: Graphics2D) {
            for (npc in npcsInScene()) {
                val color = when (npc.id) {
                    in PARASITE_STRONG_IDS -> Color(180, 0, 200) // purple
                    in PARASITE_WEAK_IDS -> Color.GREEN
                    else -> continue
                }
                val hull = npc.convexHull ?: continue
                graphics.color = color
                val originalStroke = graphics.stroke
                graphics.stroke = thickStroke
                graphics.draw(hull)
                graphics.stroke = originalStroke
            }
        }

        private fun renderTotems(graphics: Graphics2D) {
            for (npc in npcsInScene()) {
                if (npc.id !in ALL_TOTEM_IDS) continue
                val color = totemColor(npc.id) ?: continue
                val hull = npc.convexHull ?: continue
                graphics.color = color
                val originalStroke = graphics.stroke
                graphics.stroke = outlineStroke
                graphics.draw(hull)
                graphics.stroke = originalStroke
            }
        }
    }

    /**
     * Top-left status panel with phase, next-attack, parasite, sleepwalker,
     * husk, and mushroom info. Lines are suppressed when the relevant condition
     * isn't active so the panel stays tight.
     */
    private inner class NightmareStatusOverlay : Overlay() {
        private val panel = PanelComponent()

        init {
            position = OverlayPosition.TOP_LEFT
            layer = OverlayLayer.ABOVE_WIDGETS
            panel.preferredSize = Dimension(180, 0)
        }

        override fun render(graphics: Graphics2D): Dimension? {
            if (!config.statusHud()) return null
            if (nm == null) return null

            panel.children.clear()
            panel.children.add(
                TitleComponent.builder()
                    .text("Nightmare")
                    .color(Color.PINK)
                    .build()
            )

            // Phase label.
            if (phaseLabel.isNotEmpty()) {
                panel.children.add(
                    LineComponent.builder()
                        .left("Phase:")
                        .right(phaseLabel)
                        .leftColor(Color.WHITE)
                        .rightColor(Color.WHITE)
                        .build()
                )
            }

            // Next attack + ticks.
            val attack = pendingAttack
            val ticks = ticksUntilNextAttack
            if (attack != null && ticks > 0) {
                val nextColor = if (ticks <= 2) Color.RED else attack.tickColor
                panel.children.add(
                    LineComponent.builder()
                        .left("Next attack:")
                        .right("${attack.displayName} ${ticks}t")
                        .leftColor(Color.WHITE)
                        .rightColor(nextColor)
                        .build()
                )
            }

            // Curse status.
            if (cursed) {
                val left = (5 - attacksSinceCurse).coerceAtLeast(0)
                panel.children.add(
                    LineComponent.builder()
                        .left("Curse:")
                        .right("ACTIVE ($left left)")
                        .leftColor(Color.WHITE)
                        .rightColor(Color.RED)
                        .build()
                )
            }

            // Parasite countdown.
            val parasite = parasiteBurstTicks
            if (parasite > 0) {
                panel.children.add(
                    LineComponent.builder()
                        .left("Parasite:")
                        .right("${parasite}t to burst")
                        .leftColor(Color.WHITE)
                        .rightColor(if (parasite <= 5) Color.RED else Color.YELLOW)
                        .build()
                )
            }

            // Sleepwalker triage.
            val sleepwalkers = npcsInScene().filter { it.id in SLEEPWALKER_IDS }
            if (sleepwalkers.isNotEmpty()) {
                val bossLoc = nm?.worldLocation
                val close = if (bossLoc != null) {
                    sleepwalkers.count { sw ->
                        val swLoc = sw.worldLocation
                        swLoc != null && bossLoc.distanceTo(swLoc) <= 5
                    }
                } else 0
                panel.children.add(
                    LineComponent.builder()
                        .left("Sleepwalkers:")
                        .right("${sleepwalkers.size} alive, $close near boss")
                        .leftColor(Color.WHITE)
                        .rightColor(if (close > 0) Color.RED else Color.WHITE)
                        .build()
                )
            }

            // Husks alive.
            val huskCount = npcsInScene().count { it.id in HUSK_RANGED_IDS || it.id in HUSK_MAGIC_IDS }
            if (huskCount > 0) {
                panel.children.add(
                    LineComponent.builder()
                        .left("Husks:")
                        .right("$huskCount alive (kill ranged)")
                        .leftColor(Color.WHITE)
                        .rightColor(Color.RED)
                        .build()
                )
            }

            // Mushrooms.
            val mushroomCount = mushrooms.size
            if (mushroomCount > 0) {
                panel.children.add(
                    LineComponent.builder()
                        .left("Mushrooms:")
                        .right("$mushroomCount spawned")
                        .leftColor(Color.WHITE)
                        .rightColor(Color.YELLOW)
                        .build()
                )
            }

            return panel.render(graphics)
        }
    }

    /**
     * Iterable of NPCs currently in scene via the `topLevelWorldView` rather
     * than the deprecated `client.getNpcs()`. Returns an empty list if the
     * world view isn't ready yet.
     */
    private fun npcsInScene(): List<NPC> {
        val wv = client.topLevelWorldView ?: return emptyList()
        return wv.npcs().toList()
    }

    companion object {
        private val PROTECT_PRAYERS = listOf(
            Rs2PrayerEnum.PROTECT_MELEE,
            Rs2PrayerEnum.PROTECT_MAGIC,
            Rs2PrayerEnum.PROTECT_RANGE,
        )
    }
}
