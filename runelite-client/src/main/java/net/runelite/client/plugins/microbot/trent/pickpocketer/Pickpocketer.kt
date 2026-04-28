package net.runelite.client.plugins.microbot.trent.pickpocketer

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.Skill
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcQueryable
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.bankAt
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.math.Rs2Random.between as random
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.reachable.Rs2Reachable
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import javax.inject.Inject

enum class Target(
    val targetName: String,
    val numFood: Int,
    val thievingTile: WorldPoint,
    val bankTarget: Pair<Int, WorldPoint>,
    val scanRadius: Int,
) {
    MASTER_FARMER("master farmer", 2, WorldPoint(3080, 3250, 0), 10355 to WorldPoint(3091, 3245, 0), 5),
    // Elves (e.g. Oropher in Prifddinas) have a large wander path — a tight radius causes the
    // script to lose sight of them constantly and walk back to the anchor tile. 15 covers the
    // wander without scanning the entire region.
    ELF("oropher", 3, WorldPoint(3283, 6111, 0), 36559 to WorldPoint(3257, 6107, 0), 15),
    KNIGHT_OF_ARDOUGNE("knight of ardougne", 25, WorldPoint(2654, 3308, 0), 10355 to WorldPoint(2656, 3286, 0), 5),
    HERO("hero", 2, WorldPoint(2630, 3291, 0), 10355 to WorldPoint(2656, 3286, 0), 5),
}

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Pickpocketer",
    description = "Pickpockets stuff",
    tags = ["thieving"],
    enabledByDefault = false
)
class Pickpocketer : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = PickpocketerScript()

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        if (client.localPlayer != null) {
            running = true
            GlobalScope.launch { run() }
        }
    }

    private fun run() {
        while (running) {
            script.loop(client)
        }
    }

    override fun shutDown() {
        running = false
    }
}

class PickpocketerScript : StateMachineScript() {
    override fun getStartState(): State {
        return Root()
    }
}

private val TARGET = Target.ELF
private var POUCHES_TO_OPEN = 150
private val FOOD_NAME = "jug of wine"

// Leagues Mode: in OSRS Leagues a relic/passive keeps pickpocketing automatically after a
// single "Pickpocket" click, so spamming clicks every ~500ms is wasteful and can even break
// the chain. When enabled, this script clicks once and then passively watches Thieving XP —
// only re-clicking when XP has gone silent long enough to indicate the chain actually broke
// (e.g. after opening coin pouches, eating, or being knocked out of position).
private const val LEAGUES_MODE: Boolean = true

// Leagues Mode: how long Thieving XP can be silent before we assume the auto-pickpocket
// chain has broken and we need to re-click the target. 3500ms is a reasonable default —
// long enough to tolerate normal XP-drop variance, short enough to recover quickly after
// pouches are opened.
private const val SILENCE_THRESHOLD_MS = 3500L

// Leagues Mode XP-silence tracking. Seeded lazily on the first loop tick (see
// `xpTrackerSeeded`) so `lastThievingXpMs = 0` doesn't cause an immediate false positive on
// startup — we can't seed at construction time because `Root`'s init runs before we have a
// client reference. @Volatile because the state lives across the Plugin's coroutine loop.
@Volatile
private var lastThievingXp: Int = -1

@Volatile
private var lastThievingXpMs: Long = 0L

@Volatile
private var xpTrackerSeeded: Boolean = false

private class Root : State() {
    override fun checkNext(client: Client): State? {
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        // Leagues Mode: seed the XP baseline on the first tick (we don't have a client
        // handle in init{}, and seeding lazily here avoids the silence timer immediately
        // firing with lastThievingXpMs == 0).
        if (LEAGUES_MODE && !xpTrackerSeeded) {
            lastThievingXp = client.getSkillExperience(Skill.THIEVING)
            lastThievingXpMs = System.currentTimeMillis()
            xpTrackerSeeded = true
        }

        if (Rs2Player.eatAt(43)) {
            Rs2Player.waitForAnimation()
            // Eating interrupts the auto-pickpocket chain — reset the silence clock so we
            // don't spuriously trigger the re-engage branch on the next loop.
            if (LEAGUES_MODE) lastThievingXpMs = System.currentTimeMillis()
            return
        }
        if (Rs2Inventory.contains(1935)) {
            Rs2Inventory.drop(1935)
            Global.sleepUntil { !Rs2Inventory.contains(1935) }
            // Dropping a jug is fast but still a real gap in XP flow — keep the silence
            // clock honest.
            if (LEAGUES_MODE) lastThievingXpMs = System.currentTimeMillis()
            return
        }
        if (Rs2Inventory.isFull() || (!Rs2Inventory.contains(FOOD_NAME) && !Rs2Inventory.contains(1993))) {
            if (bankAt(TARGET.bankTarget.first, TARGET.bankTarget.second)) {
                Rs2Bank.depositAll()
                Rs2Bank.withdrawX(FOOD_NAME, TARGET.numFood)
                Global.sleepUntil { Rs2Inventory.hasItemAmount(FOOD_NAME, TARGET.numFood) }
                Rs2Bank.closeBank()
                Global.sleepUntil { !Rs2Bank.isOpen() }
            }
            // Banking is the biggest XP gap of all (walk-to-bank + teller + walk-back). If
            // we don't reset here, needsReEngage is guaranteed to be true when we return
            // to the NPC, which is fine — but we still want the clock anchored to "now"
            // so the subsequent re-engage click doesn't immediately re-fire again.
            if (LEAGUES_MODE) lastThievingXpMs = System.currentTimeMillis()
            return
        }
        if (Rs2Inventory.hasItemAmount("coin pouch", POUCHES_TO_OPEN)) {
            Rs2Inventory.interact("coin pouch", "open-all")
            Global.sleepUntil { !Rs2Inventory.hasItemAmount("coin pouch", POUCHES_TO_OPEN) }
            POUCHES_TO_OPEN = random(22, 27)
            // Opening pouches is the main reason Leagues Mode exists — this IS the event
            // that breaks the auto-pickpocket chain. Reset the clock so the re-engage
            // detection runs fresh from here.
            if (LEAGUES_MODE) lastThievingXpMs = System.currentTimeMillis()
            return
        }

        // Leagues Mode: update the XP-silence tracker every loop and precompute whether
        // the chain is believed to be broken. Harmless no-op when LEAGUES_MODE is false.
        val needsReEngage: Boolean = if (LEAGUES_MODE) {
            val nowXp = client.getSkillExperience(Skill.THIEVING)
            if (nowXp != lastThievingXp) {
                lastThievingXp = nowXp
                lastThievingXpMs = System.currentTimeMillis()
            }
            (System.currentTimeMillis() - lastThievingXpMs) > SILENCE_THRESHOLD_MS
        } else {
            true // Non-Leagues path always re-clicks; this value is effectively ignored below.
        }

        val exact = TARGET == Target.HERO
        val npc = Rs2NpcQueryable()
            .where { n ->
                val name = n.name ?: return@where false
                val matches =
                    if (exact) name.equals(TARGET.targetName, ignoreCase = true)
                    else name.lowercase().contains(TARGET.targetName.lowercase())
                if (!matches) return@where false
                val npcLoc = n.worldLocation ?: return@where false
                // Reachability (pathfinding) instead of LOS. Pickpocket auto-walks to the
                // target — we don't need direct line of sight, just a walkable path. LOS
                // breaks constantly when elves wander behind corners/pillars in Prifddinas
                // and was capping effective range to ~5 tiles regardless of scanRadius.
                Rs2Reachable.isReachable(npcLoc)
            }
            .within(TARGET.scanRadius)
            .first()

        // In Leagues Mode, if the chain is alive we don't care that we couldn't resolve
        // the NPC this tick (camera jitter, momentary LOS block) — just keep looping. Only
        // walk-to-target if the chain has actually broken AND we can't see the NPC.
        if (npc == null) {
            if (LEAGUES_MODE && !needsReEngage) {
                sleep(200, 400)
                return
            }
            if (!Rs2Walker.walkTo(TARGET.thievingTile, 4)) {
                Rs2Player.waitForWalking()
                // Walking back anchors the silence clock — same rationale as banking.
                if (LEAGUES_MODE) lastThievingXpMs = System.currentTimeMillis()
                return
            }
            return
        }

        if (LEAGUES_MODE) {
            if (needsReEngage) {
                // XP has gone silent → auto-pickpocket chain is broken, re-click to kick it.
                if (npc.click("pickpocket")) {
                    // Stamp the clock AFTER the click so we tolerate the normal gap before
                    // the first XP drop comes in — otherwise we'd re-click again next tick.
                    lastThievingXpMs = System.currentTimeMillis()
                    sleep(453, 722)
                }
            } else {
                // Chain alive — cheap nap so we're not busy-looping on XP polls.
                sleep(200, 400)
            }
        } else {
            // Non-Leagues path: unchanged spam-click behavior.
            if (npc.click("pickpocket")) {
                sleep(453, 722)
                if (random(0, 263) == 0)
                    sleep(5332, 10692)
            }
        }
    }
}
