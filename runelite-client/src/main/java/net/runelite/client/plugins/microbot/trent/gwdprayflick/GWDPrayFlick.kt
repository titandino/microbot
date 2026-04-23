package net.runelite.client.plugins.microbot.trent.gwdprayflick

import net.runelite.api.Actor
import net.runelite.api.Client
import net.runelite.api.MenuAction
import net.runelite.api.NPC
import net.runelite.api.events.AnimationChanged
import net.runelite.api.events.GameTick
import net.runelite.api.gameval.AnimationID
import net.runelite.api.gameval.NpcID
import net.runelite.client.eventbus.Subscribe
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * GWD Prayer Flicker — true 1-tick protection-prayer flicking for the four God
 * Wars Dungeon bosses (Graardor, K'ril, Zilyana, Kree'arra).
 *
 * ## How it works
 *
 * A "flick" means: the protection prayer is ON for exactly the single tick that
 * OSRS uses to calculate damage, and OFF on every other tick. Doing it right
 * costs only 1/6 (Graardor) to 1/4 (Kree'arra) of the prayer drain of simply
 * leaving the prayer on.
 *
 * Damage-tick rules:
 *  - Melee hits land on the SAME tick the attack animation fires.
 *  - Ranged/magic hits land `projectileTravelTicks` ticks LATER (the projectile
 *    has to fly from boss to player). The value depends on boss + attack style
 *    and is hardcoded per-mapping below.
 *
 * Strategy:
 *  1. Listen for the boss's [AnimationChanged]. This tells us style + the tick
 *     the attack was launched.
 *  2. For `delay == 0` (melee): the damage lands THIS tick. Toggle the prayer
 *     on right now, schedule it off next tick.
 *  3. For `delay > 0` (ranged/magic): enqueue a [PendingFlick] with
 *     `activateOnTick = currentTick + delay` and `deactivateOnTick + 1`. The
 *     GameTick handler flips the prayer on the exact tick required.
 *
 * ## Why per-boss state (not a single shared field)
 * Graardor's ranged attack can still be in flight when K'ril also animates (if
 * we're somehow tracking both — rare but possible with multi-combat noise).
 * Storing `PendingFlick` per NPC id prevents one boss's attack from stomping
 * on another's scheduled flick. Within a single boss we keep most-recent-wins:
 * a new animation replaces the pending entry.
 *
 * ## Why we don't call Rs2Prayer.toggle(prayer, true)
 * That entry point calls `sleepUntil(..., 10_000)` internally — it blocks the
 * calling thread until the varbit confirms the toggle. Event handlers run on
 * the RuneLite client thread, so a blocking sleepUntil there would freeze the
 * client and definitely miss the next tick. Instead we directly emit the
 * `Activate` menu invocation via [Microbot.doInvoke], which queues a click
 * that fires this same tick and returns immediately.
 */
@PluginDescriptor(
    name = PluginDescriptor.Trent + "GWD Prayer Flicker",
    description = "Tick-perfect prayer flicking for GWD bosses",
    tags = ["prayer", "combat", "gwd", "godwars", "flick"],
    enabledByDefault = false
)
class GWDPrayFlick : Plugin() {

    @Inject
    private lateinit var client: Client

    // Keyed by boss NPC id. Tracks the scheduled ON/OFF ticks for that boss's
    // most recent attack. Per-boss so two simultaneous GWD bosses can't clobber
    // each other's flicks (e.g., bodyguards adjacent, multi-combat noise).
    private val pendingByNpc: MutableMap<Int, PendingFlick> = ConcurrentHashMap()

    // Tick counter for low-prayer warning throttling.
    private var lastLowPrayerWarningTick: Int = -100

    override fun startUp() {
        pendingByNpc.clear()
        lastLowPrayerWarningTick = -100
        Microbot.log("[GWDPrayFlick] plugin started")
    }

    override fun shutDown() {
        // Turn off any prayer we turned on before disabling the plugin.
        pendingByNpc.values.forEach { pending ->
            if (pending.weTurnedItOn && Rs2Prayer.isPrayerActive(pending.prayer)) {
                invokePrayerToggle(pending.prayer)
            }
        }
        pendingByNpc.clear()
        Microbot.log("[GWDPrayFlick] plugin stopped")
    }

    @Subscribe
    fun onAnimationChanged(event: AnimationChanged) {
        if (Microbot.pauseAllScripts.get()) return
        val actor: Actor = event.actor ?: return
        if (actor !is NPC) return

        val npcId = actor.id
        val animation = actor.animation
        if (animation <= 0) return

        val mapping = BossPrayerMap.lookup(npcId, animation) ?: return

        // Only flick when the player is the target / is targeting the boss.
        // Avoids burning prayer on boss attacks aimed at other players.
        if (!isRelevantTarget(actor)) return

        if (Rs2Prayer.isOutOfPrayer()) {
            warnLowPrayer()
            return
        }
        if (Rs2Prayer.getPrayerPoints() < 5) {
            warnLowPrayer()
            // Fall through: 4 prayer is enough for one more activation.
        }

        val nowTick = client.tickCount
        val activateTick = nowTick + mapping.projectileTravelTicks
        val deactivateTick = activateTick + 1

        // If another protection prayer is already up (Range/Magic/Melee) and it
        // happens to match this attack, no need to toggle anything — but still
        // record that we are "tracking" this attack so we don't interfere.
        val alreadyCorrect = Rs2Prayer.isPrayerActive(mapping.prayer)

        val pending = PendingFlick(
            activateOnTick = activateTick,
            deactivateOnTick = deactivateTick,
            prayer = mapping.prayer,
            weTurnedItOn = false
        )

        // Same-tick case for melee (delay == 0): we must flip it on RIGHT NOW.
        // We cannot rely on GameTick to fire "this tick" because AnimationChanged
        // may arrive after GameTick already ran for the current server tick.
        if (mapping.projectileTravelTicks == 0) {
            if (!alreadyCorrect) {
                invokePrayerToggle(mapping.prayer)
                pendingByNpc[npcId] = pending.copy(weTurnedItOn = true)
            } else {
                // Prayer was already on (user/manual). Leave it; don't schedule an
                // OFF from us. Another script/user manages it.
                pendingByNpc[npcId] = pending.copy(weTurnedItOn = false)
            }
            return
        }

        // Ranged/magic: schedule for the future tick. The GameTick handler does
        // the actual activation + deactivation.
        pendingByNpc[npcId] = pending
    }

    @Subscribe
    fun onGameTick(event: GameTick) {
        if (pendingByNpc.isEmpty()) return
        val nowTick = client.tickCount
        val iterator = pendingByNpc.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val pending = entry.value

            when {
                // Activate on the scheduled tick (ranged/magic path — melee was
                // already activated synchronously in onAnimationChanged).
                nowTick == pending.activateOnTick -> {
                    if (!Rs2Prayer.isPrayerActive(pending.prayer)) {
                        invokePrayerToggle(pending.prayer)
                        entry.setValue(pending.copy(weTurnedItOn = true))
                    }
                    // else: already active (user pre-flicked); don't mark as ours.
                }

                // Deactivate on the tick AFTER the damage lands.
                nowTick == pending.deactivateOnTick -> {
                    if (pending.weTurnedItOn && Rs2Prayer.isPrayerActive(pending.prayer)) {
                        invokePrayerToggle(pending.prayer)
                    }
                    iterator.remove()
                }

                // Stale entry: scheduled activation already passed without
                // firing (e.g., we received the anim late, a GameTick was
                // skipped). Clean up so we don't leak memory.
                nowTick > pending.deactivateOnTick -> {
                    if (pending.weTurnedItOn && Rs2Prayer.isPrayerActive(pending.prayer)) {
                        invokePrayerToggle(pending.prayer)
                    }
                    iterator.remove()
                }
            }
        }
    }

    /**
     * We flick only when the boss is either interacting with us or we're
     * interacting with it. Kree'arra's AoE hits everyone on the platform so we
     * always flick for her.
     */
    private fun isRelevantTarget(boss: NPC): Boolean {
        val me = client.localPlayer ?: return false
        if (boss.interacting === me) return true
        if (me.interacting === boss) return true
        if (boss.id == NpcID.GODWARS_ARMADYL_AVATAR) return true
        return false
    }

    /**
     * Fire-and-forget prayer toggle. Unlike [Rs2Prayer.toggle], this does NOT
     * sleepUntil the varbit updates — which is essential because we're on the
     * client thread inside an event handler.
     *
     * The menu-entry payload mirrors [Rs2Prayer.invokePrayer]. The game processes
     * it on the same tick and the varbit is reflected by the next GameTick.
     */
    private fun invokePrayerToggle(prayer: Rs2PrayerEnum) {
        val entry = NewMenuEntry()
            .param0(-1)
            .param1(prayer.index)
            .opcode(MenuAction.CC_OP.id)
            .identifier(1)
            .itemId(-1)
            .option("Activate")
        Microbot.doInvoke(entry, Rs2UiHelper.getDefaultRectangle())
    }

    private fun warnLowPrayer() {
        val tick = client.tickCount
        if (tick - lastLowPrayerWarningTick < 20) return
        lastLowPrayerWarningTick = tick
        Microbot.log("[GWDPrayFlick] prayer running low — top up or the flick will fail")
    }
}

/**
 * One scheduled prayer flick. Tick values are RuneLite `Client.getTickCount()`
 * values (server ticks from session start). `weTurnedItOn` tracks whether WE
 * activated the prayer, so we don't deactivate a prayer that the user / another
 * script had on manually.
 */
private data class PendingFlick(
    val activateOnTick: Int,
    val deactivateOnTick: Int,
    val prayer: Rs2PrayerEnum,
    val weTurnedItOn: Boolean
)

/**
 * Per-boss animation → (prayer + projectile travel delay).
 *
 * Projectile travel ticks are measured from the animation tick to the damage
 * tick. Values come from OSRS Wiki Bestiary entries + tick-perfect flicker
 * videos; they are conservative defaults for a player standing at the boss's
 * typical range:
 *  - Melee: always 0 (damage lands same tick as the swing).
 *  - Ranged/magic: 1–3 ticks depending on projectile speed + distance. We use
 *    the values most commonly cited for the "standard" fight range.
 *
 * If a boss consistently "undershoots" (i.e., prayer activates one tick early
 * and player still takes damage), INCREASE the number here. If it "overshoots"
 * (damage lands and prayer turns on right after), DECREASE it.
 */
private object BossPrayerMap {

    data class AttackMapping(
        val animationId: Int,
        val prayer: Rs2PrayerEnum,
        val projectileTravelTicks: Int
    )

    // ---- General Graardor (Bandos avatar, 2215) ----
    // Melee swing: 0 ticks. Boulder throw (ranged AoE): ~2 ticks at normal
    // ranged range. Graardor's ranged projectile has a relatively slow start-up.
    private val GRAARDOR: List<AttackMapping> = listOf(
        AttackMapping(AnimationID.GODWARS_BANDOS_ATTACK, Rs2PrayerEnum.PROTECT_MELEE, 0),
        AttackMapping(AnimationID.GODWARS_BANDOS_RANGED, Rs2PrayerEnum.PROTECT_RANGE, 2),
    )

    // ---- K'ril Tsutsaroth (Zamorak avatar, 3129) ----
    // Twin-claw melee: 0 ticks. Smoke cloud magic: ~2 ticks at normal mage
    // range. Prayer-drain special shares the magic animation — Protect-Magic
    // blocks both damage + drain.
    private val KRIL: List<AttackMapping> = listOf(
        AttackMapping(AnimationID.GODWARS_ZAMORAK_ATTACK, Rs2PrayerEnum.PROTECT_MELEE, 0),
        AttackMapping(AnimationID.GODWARS_ZAMORAK_MAGIC_ATTACK, Rs2PrayerEnum.PROTECT_MAGIC, 2),
    )

    // ---- Commander Zilyana (Saradomin avatar, 2205) ----
    // Sword melee: 0 ticks. Blue-orb magic: ~1 tick (fast projectile, usually
    // fought in melee distance so travel is short).
    private val ZILYANA: List<AttackMapping> = listOf(
        AttackMapping(AnimationID.GODWARS_SARADOMIN_ATTACK, Rs2PrayerEnum.PROTECT_MELEE, 0),
        AttackMapping(AnimationID.GODWARS_SARADOMIN_MAGIC_ATTACK, Rs2PrayerEnum.PROTECT_MAGIC, 1),
    )

    // ---- Kree'arra (Armadyl avatar, 3162) ----
    // Kree'arra's animations (gameval labels "cannon"/"spear"/"sword") map to:
    //   6955 (CANNON) -> ranged feathers, ~2 ticks at platform range
    //   6956 (SPEAR)  -> magic wind, ~2 ticks
    //   6957 (SWORD)  -> melee claw, 0 ticks (only when adjacent)
    private val KREEARRA: List<AttackMapping> = listOf(
        AttackMapping(AnimationID.GODWARS_ARMADYL_CANNON_ATTACK, Rs2PrayerEnum.PROTECT_RANGE, 2),
        AttackMapping(AnimationID.GODWARS_ARMADYL_SPEAR_ATTACK, Rs2PrayerEnum.PROTECT_MAGIC, 2),
        AttackMapping(AnimationID.GODWARS_ARMADYL_SWORD_ATTACK, Rs2PrayerEnum.PROTECT_MELEE, 0),
    )

    private val BY_NPC: Map<Int, List<AttackMapping>> = mapOf(
        NpcID.GODWARS_BANDOS_AVATAR to GRAARDOR,       // General Graardor (2215)
        NpcID.GODWARS_ZAMORAK_AVATAR to KRIL,          // K'ril Tsutsaroth (3129)
        NpcID.GODWARS_SARADOMIN_AVATAR to ZILYANA,     // Commander Zilyana (2205)
        NpcID.GODWARS_ARMADYL_AVATAR to KREEARRA,      // Kree'arra (3162)
    )

    fun lookup(npcId: Int, animationId: Int): AttackMapping? =
        BY_NPC[npcId]?.firstOrNull { it.animationId == animationId }

    @Suppress("unused")
    fun supportedNpcIds(): Set<Int> = BY_NPC.keys
}
