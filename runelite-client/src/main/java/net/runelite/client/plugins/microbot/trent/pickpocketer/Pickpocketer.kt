package net.runelite.client.plugins.microbot.trent.pickpocketer

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.bankAt
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.math.Random.random
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import javax.inject.Inject

enum class Target(val targetName: String, val numFood: Int, val thievingTile: WorldPoint, val bankTarget: Pair<Int, WorldPoint>) {
    MASTER_FARMER("master farmer", 2, WorldPoint(3080, 3250, 0), 10355 to WorldPoint(3091, 3245, 0)),
    KNIGHT_OF_ARDOUGNE("knight of ardougne", 25, WorldPoint(2654, 3308, 0), 10355 to WorldPoint(2656, 3286, 0)),
    HERO("hero", 2, WorldPoint(2630, 3291, 0), 10355 to WorldPoint(2656, 3286, 0))
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

private val TARGET = Target.HERO
private var POUCHES_TO_OPEN = 40
private val FOOD_NAME = "jug of wine"

private class Root : State() {
    override fun checkNext(client: Client): State? {
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (Rs2Player.eatAt(43)) {
            Rs2Player.waitForAnimation()
            return
        }
        if (Rs2Inventory.contains(1935)) {
            Rs2Inventory.drop(1935)
            Global.sleepUntil { !Rs2Inventory.contains(1935) }
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
            return
        }
        if (Rs2Inventory.hasItemAmount("coin pouch", POUCHES_TO_OPEN)) {
            Rs2Inventory.interact("coin pouch", "open-all")
            Global.sleepUntil { !Rs2Inventory.hasItemAmount("coin pouch", POUCHES_TO_OPEN) }
            POUCHES_TO_OPEN = random(22, 27)
            return
        }
        val npc = Rs2Npc.getNpcsInLineOfSight(TARGET.targetName, if (TARGET == Target.HERO) true else false).filter { it.worldLocation.distanceTo(Rs2Player.getWorldLocation()) <= 5 }.firstOrNull()
        if (npc == null && !Rs2Walker.walkTo(TARGET.thievingTile, 4)) {
            Rs2Player.waitForWalking()
            return
        }
        if (Rs2Npc.interact(npc, "pickpocket")) {
            sleep(453, 722)
            if (random(0, 263) == 0)
                sleep(5332, 10692)
        }
    }
}
