package net.runelite.client.plugins.microbot.trent.autoprayflick

import com.google.inject.Provides
import net.runelite.api.Client
import net.runelite.api.MenuAction
import net.runelite.api.Skill
import net.runelite.api.events.GameTick
import net.runelite.api.events.MenuEntryAdded
import net.runelite.api.events.MenuOptionClicked
import net.runelite.api.gameval.InterfaceID
import net.runelite.api.gameval.VarbitID
import net.runelite.client.callback.ClientThread
import net.runelite.client.config.Config
import net.runelite.client.config.ConfigGroup
import net.runelite.client.config.ConfigItem
import net.runelite.client.config.ConfigManager
import net.runelite.client.eventbus.Subscribe
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Auto Pray Flick — GameTick-driven 1-tick quick-prayer flicker.
 *
 * ## Design (post-rewrite 2026-04-23)
 *
 * The prior revision of this file used a polling-based scheduler that tried to
 * reconstruct the tick clock from `client.tickCount` transitions and tuned two
 * axes (`offSafetyMarginMs`, `postDrainGapMs`) via an EWMA of observed round-trip
 * latency. It didn't work in practice — visible prayer drops, desyncs, drain.
 *
 * The fix: subscribe to [GameTick] and react on the canonical server-driven tick
 * clock. The `GameTick` event fires when the CLIENT observes a server tick update,
 * so any round-trip latency between client and server is already absorbed — we
 * don't need to model it. On each tick we either:
 *
 *   - **Prayer is ON**: fire a tight double-click burst (OFF → small delay → ON)
 *     so both packets land in the server's input buffer during the same tick's
 *     input-processing phase. The drain check across the tick boundary sees the
 *     transient OFF and skips the drain; the player sees prayer effectively on
 *     the entire time.
 *   - **Prayer is OFF** (desync / first-tick recovery): fire a single ON click,
 *     skip the double. Next tick resumes the normal burst.
 *
 * One adaptive variable, [flickGapMs]:
 *   - Starts at 40ms.
 *   - On drain detection (prayer point count drops while flicking), ratchet +5ms.
 *   - On desync recovery (prayer observed OFF when it should be ON), ratchet +5ms.
 *   - On sustained no-drain for [SUSTAINED_NO_DRAIN_TICKS] consecutive ticks,
 *     optionally shrink by -5ms to tighten the visible-OFF window.
 *
 * No EWMA. No two-axis tuning. No polling. Steady-state operation is silent in the
 * log; diagnostics fire only on drain, desync, gap adjustment, start, and stop.
 *
 * ## Threading
 *
 * The [GameTick] handler runs on the client thread. The two-click burst works as:
 *   1. First click: fired inline on the client thread via [client.menuAction].
 *   2. Second click: scheduled via a dedicated single-thread executor after
 *      [flickGapMs] ms, which re-hops to the client thread via [ClientThread.invoke].
 *
 * The dedicated executor is torn down in [Plugin.shutDown]; any pending second
 * click is discarded — the fail-safe is that prayer stays in its current state
 * (usually ON) when the plugin stops.
 *
 * ## Why `client.menuAction`, not `Microbot.doInvoke`
 *
 * Three consecutive revisions of this file attempted to use `Microbot.doInvoke(...)`
 * with various menu shapes. All failed: the synthetic-AWT-click pipeline +
 * `MicrobotPlugin.onMenuEntryAdded` interceptor is broken for the quick-prayer orb
 * specifically. Direct `client.menuAction(...)` dispatch from the client thread
 * bypasses VirtualMouse, the synthetic click, and the menu-swap interceptor, and
 * lands directly on the game's input-processing pipeline. Canonical reference:
 * stock RuneLite `TabInterface.java:615` (bank-tags plugin).
 *
 * 2026-04-23 empirical ground-truth invocation shape for the quick-prayer orb:
 *   - `p0 = -1`
 *   - `p1 = 10485780` (PRAYERBUTTON)
 *   - `MenuAction.CC_OP`
 *   - `identifier = 1`
 *   - `itemId = -1`
 *   - `option = "Activate"` (verb)
 *   - `target = "Quick-prayers"` (noun)
 *
 * ## Physical reality caveat
 *
 * Classic 1-tick flicking cannot achieve ZERO visible OFF window — there is always a
 * brief ([flickGapMs]) transition. 20-40ms is imperceptible (below the ~100ms
 * flicker-fusion threshold for small UI changes). That's the best physically
 * achievable. Multi-hundred-ms drops imply either packet drop (drain detection
 * ratchets [flickGapMs] up to compensate) or the plugin not firing at all (check
 * the log for a boot line).
 */
@ConfigGroup("autoPrayFlick")
interface AutoPrayFlickConfig : Config {
    /**
     * Debug capture toggle. When ON, the plugin subscribes to `MenuEntryAdded`
     * and `MenuOptionClicked` and logs (throttled) the shape of any prayer-
     * related menu entry. Useful for validating the server-accepted menu shape
     * after an OSRS revision. Default OFF: subscribers run on the client
     * thread and fire on every orb-hover frame.
     */
    @ConfigItem(
        keyName = "captureMenuShapes",
        name = "Capture menu shapes (debug)",
        description = "When ON, logs the shape of prayer-related menu entries (MenuEntryAdded + " +
            "MenuOptionClicked). Useful for validating the server-accepted shape after an OSRS " +
            "revision. Default OFF: capture runs on the client thread and fires per orb-hover " +
            "frame, so leave OFF during normal use.",
        position = 0,
    )
    fun captureMenuShapes(): Boolean = false
}

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Auto Pray Flick",
    description = "GameTick-driven 1-tick quick-prayer flicker (mouseless, drain-adaptive)",
    tags = ["prayer", "combat", "flick", "flicker"],
    enabledByDefault = false,
)
class AutoPrayFlick : Plugin() {

    @Inject
    private lateinit var config: AutoPrayFlickConfig

    /** Guice-injected RuneLite client. Receiver for direct [Client.menuAction]. */
    @Inject
    private lateinit var client: Client

    /**
     * Guice-injected [ClientThread] marshal helper. Used to re-hop the delayed
     * second click of the double-click burst back onto the client thread.
     */
    @Inject
    private lateinit var clientThread: ClientThread

    @Provides
    fun provideConfig(configManager: ConfigManager): AutoPrayFlickConfig =
        configManager.getConfig(AutoPrayFlickConfig::class.java)

    /**
     * Single-thread scheduler for the delayed second click of the double-click
     * burst. Created in [startUp], shut down in [shutDown].
     */
    private var flickScheduler: ScheduledExecutorService? = null

    /**
     * Adaptive gap (ms) between the OFF and ON clicks of the double-click burst.
     * Starts at [INITIAL_FLICK_GAP_MS], ratchets up on drain / desync, shrinks
     * slowly on sustained success.
     */
    @Volatile
    private var flickGapMs: Int = INITIAL_FLICK_GAP_MS

    /** Last-observed prayer point count. Used to detect drain across ticks. */
    @Volatile
    private var lastPrayerPoints: Int = -1

    /**
     * True if we fired a double-click on the previous [GameTick]. Used to
     * distinguish "prayer off because we never turned it on" (boot) from
     * "prayer off because our ON click of the previous burst didn't land"
     * (desync). Reset every tick.
     */
    @Volatile
    private var firedBurstLastTick: Boolean = false

    /** Counter for sustained no-drain ticks; used to decide when to shrink [flickGapMs]. */
    @Volatile
    private var consecutiveNoDrainTicks: Int = 0

    /** Have we dispatched our first click yet? Drives the one-time boot log line. */
    @Volatile
    private var bootDone: Boolean = false

    /** Lifetime stats for the shutdown summary log. */
    @Volatile
    private var totalFlicks: Long = 0L
    @Volatile
    private var drainCount: Long = 0L
    @Volatile
    private var desyncCount: Long = 0L

    /** Per-(param1, identifier) throttle state for the [captureMenuShapes] debug log. */
    private val captureLastLogMs: ConcurrentHashMap<Long, Long> = ConcurrentHashMap()

    override fun startUp() {
        flickScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "AutoPrayFlick-gap").apply { isDaemon = true }
        }
        flickGapMs = INITIAL_FLICK_GAP_MS
        lastPrayerPoints = -1
        firedBurstLastTick = false
        consecutiveNoDrainTicks = 0
        bootDone = false
        totalFlicks = 0L
        drainCount = 0L
        desyncCount = 0L

        Microbot.log(
            "[AutoPrayFlick] started (captureMenuShapes=${config.captureMenuShapes()})"
        )
        if (config.captureMenuShapes()) {
            Microbot.log(
                "[AutoPrayFlick] capture enabled: click the quick-prayer orb manually ONCE to log " +
                    "the canonical menu-entry shape. Throttled to one log per (param1, identifier) per " +
                    "${CAPTURE_THROTTLE_MS}ms. Disable after capture — subscribers run on the client " +
                    "thread and fire per orb-hover frame."
            )
        }

        // One-shot diagnostic: orb widget + quick-prayer config sanity check.
        // If no quick prayers are bound, the orb is a no-op and our clicks do
        // nothing. Better to surface that up-front than spend 30 minutes
        // debugging it.
        try {
            val orb = Rs2Widget.getWidget(InterfaceID.Orbs.PRAYERBUTTON)
            if (orb == null) {
                Microbot.log(
                    "[AutoPrayFlick] WARN: orb widget Orbs.PRAYERBUTTON " +
                        "(${InterfaceID.Orbs.PRAYERBUTTON}) not found — UI may be hidden or in a "
                            + "non-standard layout"
                )
            } else {
                val actions = orb.actions?.filterNotNull()?.filter { it.isNotEmpty() } ?: emptyList()
                Microbot.log(
                    "[AutoPrayFlick] orb widget id=${orb.id} type=${orb.type} " +
                        "actions=${actions} bounds=${orb.bounds}"
                )
            }
            val selectedMask = Microbot.getVarbitValue(VarbitID.QUICKPRAYER_SELECTED)
            val hasAny = Rs2Prayer.hasAnyQuickPrayers()
            Microbot.log(
                "[AutoPrayFlick] quickPrayerSelected mask=$selectedMask hasAnyQuickPrayers=$hasAny" +
                    if (!hasAny) " — set quick prayers in the prayer book; orb cannot toggle " +
                        "an empty quick-prayer set" else ""
            )
        } catch (e: Exception) {
            Microbot.log("[AutoPrayFlick] orb-actions dump failed: ${e.message}")
        }
    }

    override fun shutDown() {
        Microbot.log(
            "[AutoPrayFlick] stopped. stats: totalFlicks=$totalFlicks drainCount=$drainCount " +
                "desyncCount=$desyncCount finalFlickGapMs=$flickGapMs"
        )
        flickScheduler?.shutdownNow()
        flickScheduler = null
        lastPrayerPoints = -1
        firedBurstLastTick = false
        consecutiveNoDrainTicks = 0
        bootDone = false
    }

    /**
     * Core loop. Runs on the client thread. Fires after the server tick is
     * observed by the client — the canonical tick clock, no latency modeling
     * needed.
     *
     * Decision tree:
     *  1. Bail early if the quick-prayer set is empty.
     *  2. Drain detection (compares prayer points against last tick).
     *  3. Desync detection & recovery if prayer observed OFF AND we fired a
     *     burst last tick. Single ON click, ratchet [flickGapMs] up.
     *  4. Desync boot (first tick, prayer OFF). Single ON click. Next tick
     *     enters steady-state.
     *  5. Steady state: prayer ON + we've booted → fire tight double-click burst.
     */
    @Subscribe
    fun onGameTick(event: GameTick) {
        if (!Rs2Prayer.hasAnyQuickPrayers()) {
            // Orb is a no-op without a quick-prayer set. Reset transient state
            // so we don't chase phantom desyncs when the player configures one.
            firedBurstLastTick = false
            return
        }

        val currPoints = client.getBoostedSkillLevel(Skill.PRAYER)
        val observedOn = Rs2Prayer.isQuickPrayerEnabled()

        // Drain detection: if prayer point count dropped this tick AND we were
        // actively flicking (fired a burst on the previous tick), our OFF
        // didn't land before the drain check. Ratchet the gap up.
        if (lastPrayerPoints >= 0 && currPoints < lastPrayerPoints && firedBurstLastTick) {
            drainCount++
            val old = flickGapMs
            val new = (flickGapMs + DRAIN_STEP_MS).coerceAtMost(MAX_FLICK_GAP_MS)
            if (new != old) {
                flickGapMs = new
                Microbot.log(
                    "[AutoPrayFlick] drain detected: $lastPrayerPoints -> $currPoints prayer, " +
                        "flickGapMs $old -> $new"
                )
            } else {
                Microbot.log(
                    "[AutoPrayFlick] drain detected: $lastPrayerPoints -> $currPoints prayer, " +
                        "flickGapMs already at cap ($old)"
                )
            }
            consecutiveNoDrainTicks = 0
        } else if (firedBurstLastTick) {
            // No drain on a tick we flicked — success signal.
            consecutiveNoDrainTicks++
            if (consecutiveNoDrainTicks >= SUSTAINED_NO_DRAIN_TICKS) {
                val old = flickGapMs
                val new = (flickGapMs - SHRINK_STEP_MS).coerceAtLeast(MIN_FLICK_GAP_MS)
                if (new != old) {
                    flickGapMs = new
                    Microbot.log(
                        "[AutoPrayFlick] sustained no-drain for $consecutiveNoDrainTicks ticks, " +
                            "flickGapMs $old -> $new"
                    )
                    consecutiveNoDrainTicks = 0
                }
            }
        }
        lastPrayerPoints = currPoints

        // Decide what to fire this tick.
        val willFireBurst: Boolean
        if (!observedOn) {
            if (firedBurstLastTick) {
                // Desync: we fired a burst last tick but prayer is observed OFF
                // this tick → our ON click didn't land. Recovery: single ON +
                // ratchet gap up.
                desyncCount++
                val old = flickGapMs
                val new = (flickGapMs + DRAIN_STEP_MS).coerceAtMost(MAX_FLICK_GAP_MS)
                if (new != old) {
                    flickGapMs = new
                    Microbot.log(
                        "[AutoPrayFlick] desync recovery: prayer observed OFF on GameTick, " +
                            "flickGapMs $old -> $new"
                    )
                } else {
                    Microbot.log(
                        "[AutoPrayFlick] desync recovery: prayer observed OFF on GameTick, " +
                            "flickGapMs already at cap ($old)"
                    )
                }
                consecutiveNoDrainTicks = 0
                invokeQuickPrayerOrbNow()
                totalFlicks++
                willFireBurst = false
            } else {
                // Boot: first tick, prayer observed OFF. Turn it on, skip
                // burst for this tick.
                if (!bootDone) {
                    Microbot.log("[AutoPrayFlick] boot: prayer observed OFF, activating first")
                    bootDone = true
                }
                invokeQuickPrayerOrbNow()
                totalFlicks++
                willFireBurst = false
            }
        } else {
            // Prayer is ON.
            if (!bootDone) {
                Microbot.log("[AutoPrayFlick] boot: prayer observed ON, scheduling flicks")
                bootDone = true
            }
            // Fire tight double-click burst: OFF now, ON after flickGapMs.
            invokeQuickPrayerOrbNow()          // toggle OFF
            scheduleSecondClick(flickGapMs)     // toggle ON after flickGapMs
            totalFlicks++
            willFireBurst = true
        }

        firedBurstLastTick = willFireBurst
    }

    /** Throttled debug-log hook for ground-truth menu-shape capture. */
    @Subscribe
    fun onMenuOptionClicked(event: MenuOptionClicked) {
        if (!config.captureMenuShapes()) return
        val entry = event.menuEntry
        val param1 = entry.param1
        val option = entry.option ?: ""
        val target = entry.target ?: ""
        val isOrbRange = param1 in 10_485_775..10_485_785
        val mentionsPrayer = option.contains("prayer", ignoreCase = true) ||
            option.contains("quick", ignoreCase = true) ||
            target.contains("prayer", ignoreCase = true)
        if (!isOrbRange && !mentionsPrayer) return
        if (!shouldLogCapture(param1, entry.identifier)) return
        Microbot.log(
            "[AutoPrayFlick] MenuOptionClicked captured: " +
                "param0=${entry.param0} param1=${entry.param1} " +
                "identifier=${entry.identifier} menuAction=${entry.type} " +
                "option='${entry.option}' target='${entry.target}'"
        )
    }

    /** Throttled debug-log hook for ground-truth menu-shape capture. */
    @Subscribe
    fun onMenuEntryAdded(event: MenuEntryAdded) {
        if (!config.captureMenuShapes()) return
        val param1 = event.actionParam1
        val option = event.option ?: ""
        val target = event.target ?: ""
        val isOrbRange = param1 in 10_485_775..10_485_785
        val mentionsPrayer = option.contains("quick", ignoreCase = true) ||
            option.contains("prayer", ignoreCase = true) ||
            target.contains("prayer", ignoreCase = true)
        if (!isOrbRange && !mentionsPrayer) return
        if (!shouldLogCapture(param1, event.identifier)) return
        Microbot.log(
            "[AutoPrayFlick] MenuEntryAdded captured: " +
                "param0=${event.actionParam0} param1=${event.actionParam1} " +
                "identifier=${event.identifier} opcode=${event.type} " +
                "option='${event.option}' target='${event.target}'"
        )
    }

    /**
     * Fire the quick-prayer orb menu action immediately on the current
     * (client) thread. The [onGameTick] subscriber already runs on the client
     * thread, so no hop is needed for the first click of the burst.
     */
    private fun invokeQuickPrayerOrbNow() {
        try {
            client.menuAction(
                /* p0         */ -1,
                /* p1         */ QUICK_PRAYER_ORB_PARAM1,
                /* action     */ MenuAction.CC_OP,
                /* identifier */ 1,
                /* itemId     */ -1,
                /* option     */ "Activate",
                /* target     */ "Quick-prayers",
            )
        } catch (e: Exception) {
            Microbot.log("[AutoPrayFlick] menuAction failed: ${e.message}")
        }
    }

    /**
     * Schedule the second click of the double-click burst on the dedicated
     * flick scheduler. The scheduler fires the delayed task on its own
     * background thread, which then hops back to the client thread via
     * [ClientThread.invoke] to run [invokeQuickPrayerOrbNow]. The hop uses a
     * `Runnable { ... }` wrapper to disambiguate the three-way overload
     * (`Runnable` / `BooleanSupplier` / `Supplier<T>`) — a bare Kotlin lambda
     * wouldn't resolve.
     */
    private fun scheduleSecondClick(gapMs: Int) {
        val scheduler = flickScheduler ?: return
        try {
            scheduler.schedule(
                {
                    clientThread.invoke(Runnable { invokeQuickPrayerOrbNow() })
                },
                gapMs.toLong(),
                TimeUnit.MILLISECONDS,
            )
        } catch (e: Exception) {
            // Scheduler may be shutting down. Fall through silently — the next
            // tick's GameTick handler will course-correct via desync recovery
            // if the ON click never landed.
        }
    }

    /** Packs (param1, identifier) into a Long key for [captureLastLogMs]. */
    private fun captureKey(param1: Int, identifier: Int): Long =
        (param1.toLong() shl 32) or (identifier.toLong() and 0xFFFF_FFFFL)

    /**
     * Per-(param1, identifier) log throttle. Returns true at most once per
     * [CAPTURE_THROTTLE_MS] ms for each distinct key.
     */
    private fun shouldLogCapture(param1: Int, identifier: Int): Boolean {
        val key = captureKey(param1, identifier)
        val nowMs = System.currentTimeMillis()
        val lastMs = captureLastLogMs[key] ?: 0L
        if (nowMs - lastMs < CAPTURE_THROTTLE_MS) return false
        captureLastLogMs[key] = nowMs
        return true
    }
}

/**
 * Throttle interval (ms) for the menu-shape capture subscribers. Each distinct
 * (`param1`, `identifier`) pair logs at most once per this interval.
 */
private const val CAPTURE_THROTTLE_MS: Long = 3000L

/**
 * `param1` value for the quick-prayer orb menu dispatch. 2026-04-23 empirical
 * ground-truth from a manual-click capture: `param1=10485780, identifier=1,
 * opcode=57 (CC_OP), option='Activate', target='Quick-prayers'`.
 */
private const val QUICK_PRAYER_ORB_PARAM1: Int = 10485780

/**
 * Initial gap (ms) between the OFF and ON clicks of the double-click burst.
 * 40ms is much tighter than the OLD plugin's ~100ms because `client.menuAction`
 * dispatch has essentially zero client-side latency (no synthetic AWT mouse,
 * no menu-swap interceptor) compared to the OLD `Rs2Widget.clickWidget` path.
 */
private const val INITIAL_FLICK_GAP_MS: Int = 40

/** Floor for the adaptive gap. Below this, visible-OFF perceptual minimum. */
private const val MIN_FLICK_GAP_MS: Int = 20

/**
 * Ceiling for the adaptive gap. Above this we're at the edge of the tick and
 * likely causing desyncs from being too wide; stop ratcheting and log.
 */
private const val MAX_FLICK_GAP_MS: Int = 200

/** Ratchet step (ms) when drain or desync is detected. */
private const val DRAIN_STEP_MS: Int = 5

/** Shrink step (ms) after sustained no-drain. */
private const val SHRINK_STEP_MS: Int = 5

/**
 * Ticks of sustained no-drain required before shrinking [AutoPrayFlick.flickGapMs].
 * 30 ticks ≈ 18 seconds — long enough that random network jitter gets averaged
 * out, short enough that convergence isn't glacial.
 */
private const val SUSTAINED_NO_DRAIN_TICKS: Int = 30
