package net.runelite.client.plugins.microbot.trent.barbassaultplankgatherer

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.bankAt
import net.runelite.client.plugins.microbot.util.Global.sleep
import net.runelite.client.plugins.microbot.util.Global.sleepUntil
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import javax.inject.Inject

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Barb Plank Gatherer",
    description = "Gathers planks at barbarian assault",
    tags = ["plank"],
    enabledByDefault = false
)
class BarbAssaultPlankGatherer : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = BarbAssaultPlankGathererScript()

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

class BarbAssaultPlankGathererScript : StateMachineScript() {
    override fun getStartState(): State {
        return Root()
    }
}

val WORLDS = intArrayOf(320, 323, 324, 331, 332, 338, 339, 340, 347, 348, 355, 356, 357, 374, 378)
var currentWorldIndex = 0

val PLANK_LOCATION = WorldPoint(2553, 3577, 0)

private class Root : State() {
    override fun checkNext(client: Client): State? {
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (Rs2Player.isWalking())
            return
        if (Rs2Inventory.isFull()) {
            if (bankAt(19051, WorldPoint(2537, 3573, 0))) {
                Rs2Bank.depositAll()
                sleepUntil { Rs2Inventory.isEmpty() }
                Rs2Bank.closeBank()
                sleepUntil { !Rs2Bank.isOpen() }
            }
            return
        }
        if (Rs2Player.getWorldLocation().distanceTo(PLANK_LOCATION) >= 7) {
            if (Rs2Walker.walkMiniMap(PLANK_LOCATION, 3))
                Rs2Player.waitForWalking()
            return
        }
        val planks = Rs2GroundItem.getAll(8).filter { it.item?.name == "Plank" }
        if (planks.isEmpty()) {
            hop()
            return
        }
        planks.forEach {
            if (Rs2GroundItem.interact(it, "take")) {
                Rs2Player.waitForWalking()
                sleep(600, 816)
            }
        }
    }

    fun hop() {
        Microbot.hopToWorld(WORLDS[currentWorldIndex])
        currentWorldIndex = (currentWorldIndex + 1) % WORLDS.size
    }
}